package com.truesight.backend.ingestion.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, dependency-free RFC-4180-ish CSV tokenizer: handles quoted fields (including
 * embedded commas and embedded newlines inside a quoted field), doubled-quote escaping
 * ("" inside a quoted field means a literal quote character), and a UTF-8 byte-order
 * mark at the start of the file.
 *
 * <p>Why hand-rolled instead of a library (Apache Commons CSV, OpenCSV): this is a
 * genuine trade-off, not a default. A real CSV dialect has more edge cases than this
 * class handles (custom delimiters, different quote characters, various line-ending
 * conventions beyond \\n / \\r\\n). For a reference implementation whose CSV input is
 * "portfolio holdings exported from an institutional PM system", the RFC-4180 subset
 * here is enough, and keeping it in ~80 lines the team can read start to finish beats
 * pulling in a library whose full feature surface this app doesn't need. If real-world
 * files start breaking this parser, that is the signal to switch to a library — not a
 * sign this class was the wrong call at the time it was written.
 */
public final class SimpleCsvTokenizer {

    private SimpleCsvTokenizer() {
    }

    public static List<List<String>> tokenize(String content) {
        String withoutBom = stripByteOrderMark(content);
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasAnyContent = false;

        int i = 0;
        int len = withoutBom.length();
        while (i < len) {
            char c = withoutBom.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && withoutBom.charAt(i + 1) == '"') {
                        field.append('"'); // escaped quote
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    rowHasAnyContent = true;
                    i++;
                }
                case ',' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rowHasAnyContent = true;
                    i++;
                }
                case '\r' -> i++; // ignore; \n (bare or following \r) ends the row below
                case '\n' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (rowHasAnyContent || currentRow.size() > 1) {
                        rows.add(currentRow);
                    }
                    currentRow = new ArrayList<>();
                    rowHasAnyContent = false;
                    i++;
                }
                default -> {
                    field.append(c);
                    rowHasAnyContent = true;
                    i++;
                }
            }
        }
        // Final field/row if the file doesn't end with a trailing newline.
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }

        return rows;
    }

    private static String stripByteOrderMark(String content) {
        if (!content.isEmpty() && content.charAt(0) == '﻿') {
            return content.substring(1);
        }
        return content;
    }
}
