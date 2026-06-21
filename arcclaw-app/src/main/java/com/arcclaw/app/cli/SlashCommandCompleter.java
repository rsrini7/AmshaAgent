package com.arcclaw.app.cli;

import java.util.List;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * Tab-completes slash commands for the ArcClaw interactive REPL.
 * Completes when the input starts with '/' and has no spaces (single-token input).
 */
public class SlashCommandCompleter implements Completer {

    private final List<Candidate> candidates;

    public SlashCommandCompleter(List<String> commands) {
        this.candidates = commands.stream()
                .sorted()
                .map(cmd -> new Candidate(cmd, cmd, null, null, null, null, true))
                .toList();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.wordIndex() == 0 && line.word().startsWith("/")) {
            String prefix = line.word();
            for (Candidate c : this.candidates) {
                if (c.value().startsWith(prefix)) {
                    candidates.add(c);
                }
            }
        }
    }
}
