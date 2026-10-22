package com.codexgui.changelist.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A standalone child process, with no network or Codex dependency. */
public final class FakeCodex {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("SLEEP_CHILD")) {
            Files.writeString(Path.of("child-pid.tmp"), Long.toString(ProcessHandle.current().pid()));
            Files.move(Path.of("child-pid.tmp"), Path.of("child-pid.txt"));
            Thread.sleep(20_000); return;
        }
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("pid.txt"), Long.toString(ProcessHandle.current().pid()));
        if (prompt.equals("SLEEP_WITH_CHILD")) {
            String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), FakeCodex.class.getName(), "SLEEP_CHILD").inheritIO().start();
            Thread.sleep(20_000);
        }
        if (prompt.equals("SLEEP")) Thread.sleep(20_000);
        if (prompt.equals("FAIL")) { System.err.print("fixture error"); System.exit(7); }
        Path output = null;
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--output-last-message")) output = Path.of(args[i + 1]);
        Files.writeString(output, prompt, StandardCharsets.UTF_8);
        System.out.println("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":321,\"cached_input_tokens\":120,\"output_tokens\":42}}");
    }
}
