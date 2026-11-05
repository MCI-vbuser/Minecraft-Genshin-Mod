import javax.net.ssl.*;
import java.security.cert.X509Certificate;

/** Print the certificate chain the PC actually sees for a host (trust-all). */
public class CertProbe {
    public static void main(String[] a) throws Exception {
        String host = a.length > 0 ? a[0] : "repo.maven.apache.org";
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String t) { }
            public void checkServerTrusted(X509Certificate[] c, String t) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocketFactory f = ctx.getSocketFactory();
        SSLSocket s = (SSLSocket) f.createSocket();
        s.connect(new java.net.InetSocketAddress(host, 443), 8000);
        s.setSoTimeout(8000);
        javax.net.ssl.SSLParameters p = s.getSSLParameters();
        p.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
        s.setSSLParameters(p);
        s.startHandshake();
        java.security.cert.Certificate[] chain = s.getSession().getPeerCertificates();
        for (java.security.cert.Certificate c : chain) {
            X509Certificate x = (X509Certificate) c;
            System.out.println("SUBJECT: " + x.getSubjectX500Principal().getName());
            System.out.println("ISSUER : " + x.getIssuerX500Principal().getName());
        }
        s.close();
    }
}
