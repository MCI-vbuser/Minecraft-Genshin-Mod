import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;

/** 自检：通过本地缓存代理拉一个 URL，打印状态码和正文长度。 */
public class SelfTest {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0]
                : "https://files.minecraftforge.net/maven/net/minecraftforge/artifactural/maven-metadata.xml";
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        System.out.println("status = " + code);
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            in.close();
        }
        System.out.println("bytes  = " + buf.size());
        String body = new String(buf.toByteArray(), "UTF-8");
        System.out.println(body.length() > 600 ? body.substring(0, 600) + "..." : body);
    }
}
