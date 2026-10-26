"""Copy verified, idle CodeDeck chats into its private home, then natively archive originals.

Preview: python tools/migrate-codex-history.py --codex <codex.exe>
Apply:   python tools/migrate-codex-history.py --codex <codex.exe> --apply
Uses Python's standard library; never edits the desktop's state database or deletes logs.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import sqlite3
import subprocess
import threading

SOURCES = ["cli", "vscode", "appServer", "exec", "unknown", "subAgent", "subAgentReview",
           "subAgentCompact", "subAgentThreadSpawn", "subAgentOther"]


class Rpc:
    def __init__(self, executable, home, private=False, name="codedeck_history_migration"):
        args = [str(executable)]
        if private:
            args += ["-c", "sqlite_home=" + json.dumps(str(home))]
        args += ["app-server", "--stdio"]
        env = dict(os.environ, CODEX_HOME=str(home))
        env.pop("CODEX_INTERNAL_ORIGINATOR_OVERRIDE", None)
        if private:
            env["CODEX_SQLITE_HOME"] = str(home)
        self.process = subprocess.Popen(args, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.responses = queue.Queue()
        self.notifications = queue.Queue()
        self.serial = 0
        threading.Thread(target=self._read, daemon=True).start()
        self.call("initialize", {"clientInfo": {"name": name, "version": "2.1.5"},
                                 "capabilities": {"experimentalApi": True}})
        self.process.stdin.write(json.dumps({"method": "initialized"}) + "\n")
        self.process.stdin.flush()

    def _read(self):
        for line in self.process.stdout:
            try:
                message = json.loads(line)
                if "id" in message:
                    self.responses.put(message)
                elif message.get("method") == "turn/completed":
                    self.notifications.put(message)
            except ValueError:
                pass

    def call(self, method, params):
        self.serial += 1
        self.process.stdin.write(json.dumps({"id": self.serial, "method": method, "params": params}) + "\n")
        self.process.stdin.flush()
        while True:
            message = self.responses.get(timeout=30)
            if message.get("id") != self.serial:
                continue
            if "error" in message:
                raise RuntimeError(message["error"]["message"])
            return message["result"]

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()


@contextlib.contextmanager
def idle_writer(home, thread_id):
    """Probe the same per-thread OS lock used by app-server; skip live writers."""
    path = home / "thread-writer-locks" / (thread_id + ".lock")
    if not path.exists():
        yield
        return
    with path.open("r+b") as lock:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            try:
                yield
            finally:
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)


def initialize_home(source, target):
    target.mkdir(parents=True, exist_ok=True)
    marker = target / ".initial-import-complete"
    if marker.exists():
        return
    for name in ("config.toml", "auth.json", ".credentials.json", "models_cache.json", "AGENTS.md",
                 "AGENTS.override.md", "instructions.md", "requirements.toml"):
        if (source / name).is_file() and not (target / name).exists():
            shutil.copy2(source / name, target / name)
    for name in ("skills", "plugins", "rules", "prompts", "hooks"):
        original, link = source / name, target / name
        if not original.is_dir() or link.exists():
            continue
        if os.name == "nt":
            quote = lambda path: "'" + str(path).replace("'", "''") + "'"
            subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                            "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path " + quote(link)
                            + " -Value " + quote(original) + " | Out-Null"], check=True,
                           creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            link.symlink_to(original, target_is_directory=True)
    marker.write_text("CodeDeck isolated Codex home\n", encoding="utf-8")


def copy_projection(source, target, thread_id):
    """Copy just this chat's projection rows, with identical native schemas and ordinals."""
    for original in source.glob("thread_history_*.sqlite"):
        destination = target / original.name
        if not destination.exists():
            raise RuntimeError("Native history schema is unavailable: " + destination.name)
        with sqlite3.connect(destination, timeout=30, uri=True) as db:
            db.execute("ATTACH DATABASE ? AS source_history", (original.resolve().as_uri() + "?mode=ro",))
            tables = db.execute("SELECT name FROM source_history.sqlite_master WHERE type='table'").fetchall()
            for (name,) in tables:
                quoted = '"' + name.replace('"', '""') + '"'
                columns = [row[1] for row in db.execute("PRAGMA source_history.table_info(" + quoted + ")")]
                if "thread_id" not in columns:
                    continue
                target_columns = [row[1] for row in db.execute("PRAGMA main.table_info(" + quoted + ")")]
                if columns != target_columns:
                    raise RuntimeError("Native history schemas differ; original left intact: " + name)
                cols = ",".join('"' + column.replace('"', '""') + '"' for column in columns)
                db.execute("INSERT OR IGNORE INTO main." + quoted + " (" + cols + ") SELECT " + cols
                           + " FROM source_history." + quoted + " WHERE thread_id=?", (thread_id,))
                source_count = db.execute("SELECT count(*) FROM source_history." + quoted + " WHERE thread_id=?", (thread_id,)).fetchone()[0]
                target_count = db.execute("SELECT count(*) FROM main." + quoted + " WHERE thread_id=?", (thread_id,)).fetchone()[0]
                if source_count != target_count:
                    raise RuntimeError("Projection row count differs; original left intact: " + name)


def turns(client, thread_id):
    result, cursor = [], None
    while True:
        params = {"threadId": thread_id, "limit": 100, "sortDirection": "asc", "itemsView": "full"}
        if cursor:
            params["cursor"] = cursor
        page = client.call("thread/turns/list", params)
        result.extend(page["data"])
        cursor = page.get("nextCursor")
        if not cursor:
            return result


def catalog(client, repair=False):
    result, cursor, seen = [], None, set()
    while True:
        params = {"limit": 100, "sourceKinds": SOURCES, "useStateDbOnly": not repair}
        if cursor:
            params["cursor"] = cursor
        page = client.call("thread/list", params)
        result.extend(page["data"])
        cursor = page.get("nextCursor")
        if not cursor:
            return result
        if cursor in seen:
            raise RuntimeError("Repeated history cursor")
        seen.add(cursor)


def sha256(path):
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def migrate(executable, source, target, apply=False):
    report = {"source": str(source), "target": str(target), "apply": apply, "migrated": [], "eligible": [], "skipped": []}
    original = Rpc(executable, source)
    private = None
    try:
        if apply:
            initialize_home(source, target)
            private = Rpc(executable, target, private=True)
        config = original.call("config/read", {"includeLayers": False}).get("config", {})
        sqlite_home = Path(config.get("sqlite_home") or os.environ.get("CODEX_SQLITE_HOME") or source)
        all_threads = catalog(original)
        verified = {}
        for thread in all_threads:
            try:
                if not thread.get("path"):
                    continue
                path = Path(thread["path"]).resolve()
                try:
                    relative = path.relative_to((source / "sessions").resolve())
                except ValueError:
                    continue
                with path.open(encoding="utf-8") as stream:
                    metadata = json.loads(stream.readline()).get("payload", {})
                if metadata.get("originator") not in ("codedeck-jetbrains", "codex-gui-jetbrains"):
                    continue
                thread_id = thread["id"]
                if metadata.get("id") != thread_id:
                    continue
                report["eligible"].append(thread_id)
                if not apply:
                    continue
                with idle_writer(source, thread_id), idle_writer(target, thread_id):
                    destination = target / "sessions" / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    expected = sha256(path)
                    if destination.exists() and sha256(destination) != expected:
                        raise RuntimeError("A different private copy already exists; neither copy was replaced")
                    if not destination.exists():
                        temporary = destination.with_suffix(".importing")
                        shutil.copy2(path, temporary)
                        if sha256(temporary) != expected:
                            raise RuntimeError("Transcript changed during copying; original left intact")
                        temporary.replace(destination)
                    catalog(private, repair=True)
                    # Let the same native binary create its supported projection schema first.
                    turns(private, thread_id)
                    copy_projection(sqlite_home, target, thread_id)
                    before, after = turns(original, thread_id), turns(private, thread_id)
                    if before != after:
                        raise RuntimeError("Native turn histories differ; original left intact")
                    title = thread.get("name")
                    if title:
                        private.call("thread/name/set", {"threadId": thread_id, "name": title})
                        (target / "chat-titles").mkdir(exist_ok=True)
                        (target / "chat-titles" / (thread_id + ".txt")).write_text(title, encoding="utf-8")
                    verified[thread_id] = (path, expected)
            except (OSError, ValueError, RuntimeError, sqlite3.Error) as error:
                report["skipped"].append({"id": thread["id"], "reason": str(error)})
        # Snapshot the complete catalog before archiving, so pagination cannot skip moved rows.
        parents = {thread["id"]: thread.get("parentThreadId") for thread in all_threads}
        def ancestors(thread_id):
            result = set()
            parent = parents.get(thread_id)
            while parent and parent not in result:
                result.add(parent)
                parent = parents.get(parent)
            return result
        for thread_id in sorted(verified, key=lambda value: len(ancestors(value)), reverse=True):
            try:
                # Native archive also archives descendants. Never hide an unverified or foreign child.
                if any(thread_id in ancestors(child) and child not in report["migrated"] for child in parents):
                    raise RuntimeError("An active, foreign or unverified descendant remains; original left intact")
                path, expected = verified[thread_id]
                with idle_writer(source, thread_id):
                    if sha256(path) != expected:
                        raise RuntimeError("Original changed after verification; original left intact")
                original.call("thread/archive", {"threadId": thread_id})
                report["migrated"].append(thread_id)
            except (OSError, RuntimeError) as error:
                report["skipped"].append({"id": thread_id, "reason": str(error)})
    finally:
        original.close()
        if private:
            private.close()
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True, type=Path)
    parser.add_argument("--source", type=Path, default=Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))))
    parser.add_argument("--target", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    target = (args.target or source / "codedeck" / "jetbrains").resolve()
    if target == source or target.is_relative_to(source / "sessions") or source.is_relative_to(target):
        parser.error("The target must be separate from the original home and its session directories")
    report = migrate(args.codex.resolve(), source, target, args.apply)
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"eligible": len(report["eligible"]), "migrated": len(report["migrated"]), "skipped": report["skipped"]}, ensure_ascii=False))
