package com.travel.planner.transit;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GTFS 의 txt(CSV) 파일을 한 줄씩 읽는다. 큰따옴표로 감싼 값(쉼표·줄바꿈·"" 포함), BOM, CRLF 를 처리한다.
 * 파일이 수백 MB 일 수 있어 전체를 메모리에 올리지 않는다.
 */
final class GtfsCsv implements Closeable {

    private final BufferedReader reader;
    private final Map<String, Integer> columns = new HashMap<>();
    private final StringBuilder field = new StringBuilder();
    private final List<String> row = new ArrayList<>();
    private String[] current;
    private boolean eof;

    GtfsCsv(InputStream in) throws IOException {
        reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String[] header = readRow();
        if (header != null) {
            for (int i = 0; i < header.length; i++) {
                String name = header[i].trim().toLowerCase();
                if (!name.isEmpty() && name.charAt(0) == '﻿') name = name.substring(1);
                columns.putIfAbsent(name, i);
            }
        }
    }

    boolean has(String column) {
        return columns.containsKey(column);
    }

    /** 다음 줄로 넘어간다. 더 없으면 false */
    boolean next() throws IOException {
        do {
            current = readRow();
        } while (current != null && current.length == 1 && current[0].isEmpty());   // 빈 줄은 건너뛴다
        return current != null;
    }

    /** 현재 줄에서 그 열의 값. 열이 없거나 비어 있으면 "" */
    String get(String column) {
        Integer index = columns.get(column);
        if (index == null || index >= current.length) return "";
        return current[index].trim();
    }

    private String[] readRow() throws IOException {
        if (eof) return null;
        row.clear();
        field.setLength(0);
        boolean quoted = false;
        boolean any = false;
        while (true) {
            int c = reader.read();
            if (c < 0) {
                eof = true;
                if (!any) return null;
                break;
            }
            any = true;
            if (quoted) {
                if (c == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next >= 0) reader.reset(); else eof = true;
                    }
                } else {
                    field.append((char) c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                break;
            } else if (c != '\r') {
                field.append((char) c);
            }
        }
        row.add(field.toString());
        return row.toArray(new String[0]);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
