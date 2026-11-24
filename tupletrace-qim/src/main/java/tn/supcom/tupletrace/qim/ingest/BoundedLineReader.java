package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Lit un flux ligne par ligne, en octets, avant tout décodage. Une ligne trop longue est refusée
 * dès son 16 385e octet et un fichier trop gros dès son premier octet en trop : on ne garde en
 * mémoire qu'un bloc de lecture et une ligne. Fin de ligne LF ou CRLF ; BOM UTF-8 initial ignoré ;
 * décodage UTF-8 strict.
 */
final class BoundedLineReader {
    private static final int CHUNK = 8192;

    private final InputStream in;
    private final CsvLimits limits;
    private final byte[] chunk = new byte[CHUNK];
    private final byte[] line;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private int position;
    private int end;
    private long totalBytes;
    private long lineNumber;
    private boolean endOfStream;

    BoundedLineReader(InputStream in, CsvLimits limits) {
        this.in = Objects.requireNonNull(in);
        this.limits = Objects.requireNonNull(limits);
        // +1 octet pour accueillir un éventuel CR final, retiré avant le contrôle de longueur.
        this.line = new byte[limits.maxLineBytes() + 1];
    }

    /** Ligne suivante sans sa fin de ligne, ou {@code null} à la fin du flux. */
    String readLine() throws IOException {
        int length = 0;
        boolean readSomething = false;
        while (true) {
            if (position == end && !fill()) {
                if (!readSomething) {
                    return null;
                }
                break;
            }
            byte b = chunk[position++];
            readSomething = true;
            if (b == '\n') {
                break;
            }
            if (length == line.length) {
                throw new CsvRejectedException(Code.LINE_TOO_LONG, lineNumber + 1);
            }
            line[length++] = b;
        }
        lineNumber++;
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }
        if (length > limits.maxLineBytes()) {
            throw new CsvRejectedException(Code.LINE_TOO_LONG, lineNumber);
        }
        int offset = 0;
        if (lineNumber == 1 && length >= 3
                && line[0] == (byte) 0xEF && line[1] == (byte) 0xBB && line[2] == (byte) 0xBF) {
            offset = 3;
        }
        try {
            return decoder.decode(ByteBuffer.wrap(line, offset, length - offset)).toString();
        } catch (CharacterCodingException invalid) {
            throw new CsvRejectedException(Code.INVALID_UTF8, lineNumber);
        }
    }

    /** Numéro de la dernière ligne lue (1 = première ligne du fichier). */
    long lineNumber() {
        return lineNumber;
    }

    private boolean fill() throws IOException {
        if (endOfStream) {
            return false;
        }
        int read;
        do {
            read = in.read(chunk, 0, CHUNK);
        } while (read == 0);
        if (read < 0) {
            endOfStream = true;
            return false;
        }
        totalBytes += read;
        if (totalBytes > limits.maxBytes()) {
            throw new CsvRejectedException(Code.FILE_TOO_LARGE, lineNumber + 1);
        }
        position = 0;
        end = read;
        return true;
    }
}
