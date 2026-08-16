import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestSearchChain2 {
    static String get(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String key = "sk-E8D4C39ADA57026CC18FA168BC65";
        String query = URLEncoder.encode("海阔天空", StandardCharsets.UTF_8);
        String searchJson = get("https://music.163.com/api/search/get/web?s=" + query + "&type=1&limit=5&offset=0", "https://music.163.com/");
        // 提取第一首歌曲的 id / name / artist
        Pattern idP = Pattern.compile("\"id\":(\\d{6,})");
        Matcher idm = idP.matcher(searchJson);
        String id = idm.find() ? idm.group(1) : "NONE";
        Pattern nameP = Pattern.compile("\"name\":\"([^\"]{2,40})\"");
        Matcher nm = nameP.matcher(searchJson);
        String name = nm.find() ? nm.group(1) : "NONE";
        System.out.println("搜索: id=" + id + " name=" + name);
        // 柠柚解析
        String parseJson = get("https://api.nycnm.cn/api/v2/163music?ids=" + id + "&level=standard&type=json&apikey=" + key, null);
        Pattern stP = Pattern.compile("\"status\":(\\d+)");
        Matcher stm = stP.matcher(parseJson);
        String status = stm.find() ? stm.group(1) : "?";
        Pattern urlP = Pattern.compile("\"url\":\"([^\"]{50,})\"");
        Matcher urlm = urlP.matcher(parseJson);
        String url = urlm.find() ? urlm.group(1) : "NONE";
        System.out.println("柠柚解析: status=" + status + " url长度=" + url.length());
        System.out.println("完整链路 " + (status.equals("200") && url.length() > 100 ? "OK ✅" : "失败 ❌"));
    }
}
