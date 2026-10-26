"""Offline native protocol acceptance: python tools/test-session-isolation.py --codex <codex.exe>."""
import argparse
import hashlib
import http.server
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location("migration", Path(__file__).with_name("migrate-codex-history.py"))
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)
requests = []


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append(body)
        message = {"id": "msg_fixture_" + str(len(requests)), "type": "message", "role": "assistant",
                   "status": "completed", "phase": "final_answer",
                   "content": [{"type": "output_text", "text": "Saved offline fixture answer.", "annotations": []}]}
        response = {"id": "resp_fixture_" + str(len(requests)), "object": "response", "created_at": int(time.time()),
                    "status": "completed", "model": "gpt-6.1-sol", "output": [message],
                    "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}
        events = [
            {"type": "response.created", "response": dict(response, status="in_progress", output=[])},
            {"type": "response.output_item.added", "output_index": 0, "item": dict(message, status="in_progress", content=[])},
            {"type": "response.output_text.delta", "item_id": message["id"], "output_index": 0, "content_index": 0, "delta": "Saved offline fixture answer."},
            {"type": "response.output_item.done", "output_index": 0, "item": message},
            {"type": "response.completed", "response": response},
        ]
        data = "".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n" for event in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def turn(client, chat_id, text):
    started = client.call("turn/start", {"threadId": chat_id, "input": [{"type": "text", "text": text}]})
    turn_id = started["turn"]["id"]
    while True:
        event = client.notifications.get(timeout=30)
        if event["params"].get("threadId") == chat_id and event["params"]["turn"]["id"] == turn_id:
            assert event["params"]["turn"]["status"] == "completed", event
            break
    for _ in range(200):
        try:
            values = migration.turns(client, chat_id)
        except RuntimeError as error:
            if "is empty" not in str(error) and "not found" not in str(error):
                raise
            time.sleep(0.05)
            continue
        saved = next((value for value in values if value["id"] == turn_id), None)
        if saved and saved["status"] in ("completed", "failed", "interrupted"):
            assert saved["status"] == "completed", saved
            return values
        time.sleep(0.05)
    raise AssertionError("Fixture turn did not finish")


def run(cli):
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    root = Path(tempfile.mkdtemp(prefix="session-isolation-", dir=Path(__file__).resolve().parent.parent / ".gradle"))
    source = root / "shared"
    target = source / "codedeck" / "jetbrains"
    source.mkdir()
    config = source / "config.toml"
    config.write_text('model_provider="fixture"\nmodel="gpt-6.1-sol"\nweb_search="disabled"\n'
                      '[model_providers.fixture]\nname="Offline fixture"\nwire_api="responses"\n'
                      'base_url="http://127.0.0.1:' + str(server.server_port) + '/v1"\nrequires_openai_auth=false\n'
                      '[features]\nplugins=false\napps=false\nhooks=false\n', encoding="utf-8")
    source_config = config.read_bytes()
    original = migration.Rpc(cli, source, name="codedeck-jetbrains")
    try:
        started = original.call("thread/start", {"cwd": str(root), "ephemeral": False, "approvalPolicy": "never", "sandbox": "read-only", "threadSource": "codedeck"})
        chat_id = started["thread"]["id"]
        before = turn(original, chat_id, "isolation_original_context_中文")
        assert len(before) == 1 and before[0]["items"], before
        original.call("thread/name/set", {"threadId": chat_id, "name": "隔离迁移验收"})
        # A live owner must be skipped; its session is not archived or copied.
        busy = migration.migrate(cli, source, target, apply=True)
        assert busy["skipped"] and not busy["migrated"], busy
    finally:
        original.close()
    desktop = migration.Rpc(cli, source, name="codex_desktop")
    try:
        desktop_chat = desktop.call("thread/start", {"cwd": str(root), "ephemeral": False, "approvalPolicy": "never", "sandbox": "read-only"})["thread"]["id"]
        turn(desktop, desktop_chat, "Desktop-only conversation must stay in the sidebar.")
    finally:
        desktop.close()
    report = migration.migrate(cli, source, target, apply=True)
    assert report["migrated"] == [chat_id], report
    assert config.read_bytes() == source_config
    original = migration.Rpc(cli, source)
    private = migration.Rpc(cli, target, private=True)
    try:
        opts = {"limit": 100, "useStateDbOnly": True, "sourceKinds": ["cli", "vscode", "appServer", "exec", "unknown"]}
        assert [thread["id"] for thread in original.call("thread/list", opts)["data"]] == [desktop_chat]
        assert [thread["id"] for thread in private.call("thread/list", opts)["data"]] == [chat_id]
        assert migration.turns(private, chat_id) == before
        private.call("thread/resume", {"threadId": chat_id, "excludeTurns": True})
        continued = turn(private, chat_id, "Continue this independent plugin conversation.")
        assert len(continued) == 2
        assert "isolation_original_context_中文" in json.dumps(requests[-1], ensure_ascii=False), "Resumed model request lost the old context"
        private_id = private.call("thread/start", {"cwd": str(root), "ephemeral": False, "approvalPolicy": "never", "sandbox": "read-only", "threadSource": "codedeck"})["thread"]["id"]
        turn(private, private_id, "new_private_chat_marker")
        # Even the desktop's scan-and-repair path must not discover nested private sessions.
        assert private_id not in [thread["id"] for thread in original.call("thread/list", dict(opts, useStateDbOnly=False))["data"]]
        assert config.read_bytes() == source_config
        result = {"root": str(root), "migrated": chat_id, "desktopPreserved": desktop_chat,
                  "privateNewChat": private_id, "continuedTurns": len(continued), "modelContextPreserved": True,
                  "sourceConfigUnchanged": True, "busyOwnerSkipped": True, "passed": True}
        (root / "report.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
    finally:
        original.close()
        private.close()
        server.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True, type=Path)
    run(parser.parse_args().codex.resolve())
