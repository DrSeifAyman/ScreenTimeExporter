import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;

public class PrintSha1 {
    public static void main(String[] args) throws Exception {
        FileInputStream is = new FileInputStream("screentime-release.jks");
        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(is, "screentime2026".toCharArray());
        Certificate cert = keystore.getCertificate("screentime");
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] der = cert.getEncoded();
        md.update(der);
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            sb.append(String.format("%02X", digest[i]));
            if (i < digest.length - 1) sb.append(":");
        }
        System.out.println("SHA-1: " + sb.toString());
    }
}
