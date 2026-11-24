package tn.supcom.tupletrace.qim.ingest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongFunction;

/**
 * Flux CSV synthétique fabriqué ligne par ligne au moment de la lecture : le fichier complet
 * n'existe jamais en mémoire. Compte les octets réellement servis au lecteur.
 */
final class GeneratedCsv extends InputStream {
    private final String header;
    private final long rows;
    private final LongFunction<String> row;
    private long next = -1;
    private byte[] current;
    private int position;
    private long served;

    GeneratedCsv(String header, long rows, LongFunction<String> row) {
        this.header = header;
        this.rows = rows;
        this.row = row;
    }

    long bytesServed() {
        return served;
    }

    @Override
    public int read() {
        if (!ensure()) {
            return -1;
        }
        served++;
        return current[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        if (!ensure()) {
            return -1;
        }
        int count = Math.min(length, current.length - position);
        System.arraycopy(current, position, buffer, offset, count);
        position += count;
        served += count;
        return count;
    }

    private boolean ensure() {
        while (current == null || position == current.length) {
            if (next >= rows) {
                return false;
            }
            var text = next < 0 ? header : row.apply(next);
            current = (text + "\n").getBytes(StandardCharsets.UTF_8);
            position = 0;
            next++;
        }
        return true;
    }
}
