package tn.supcom.tupletrace.security;

import org.paseto4j.commons.PreAuthenticationEncoder;
import org.paseto4j.commons.Token;
import org.paseto4j.commons.TokenOut;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.paseto4j.commons.Purpose.PURPOSE_PUBLIC;
import static org.paseto4j.commons.Version.V4;

final class V4Public {
    private V4Public() {}

    static String sign(EdECPrivateKey key, String payload, String footer) throws GeneralSecurityException {
        var token = new TokenOut(V4, PURPOSE_PUBLIC);
        byte[] message = payload.getBytes(UTF_8);
        var ed = Signature.getInstance("Ed25519");
        ed.initSign(key);
        ed.update(PreAuthenticationEncoder.encode(token.header(), message, footer.getBytes(UTF_8), new byte[0]));
        byte[] signed = ByteBuffer.allocate(message.length + 64).put(message).put(ed.sign()).array();
        return token.payload(signed).footer(footer).doFinal();
    }

    static String verify(EdECPublicKey key, String encoded, String footer, String assertion)
            throws GeneralSecurityException {
        var token = new Token(encoded, V4, PURPOSE_PUBLIC, footer);
        byte[] signed = Base64.getUrlDecoder().decode(token.getPayload());
        if (signed.length < 64) throw new SignatureException("Invalid signature length");
        int boundary = signed.length - 64;
        byte[] message = Arrays.copyOf(signed, boundary);
        var ed = Signature.getInstance("Ed25519");
        ed.initVerify(key);
        ed.update(PreAuthenticationEncoder.encode(token.header(), message, footer.getBytes(UTF_8), assertion.getBytes(UTF_8)));
        if (!ed.verify(signed, boundary, 64)) throw new SignatureException("Invalid signature");
        return new String(message, UTF_8);
    }
}
