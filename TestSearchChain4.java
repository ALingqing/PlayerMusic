import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;

public class TestSearchChain4 {
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
        String query = URLEncoder.encode("海阔天空", StandardCharsets.UTF_8.toString());
        String searchJson = get("https://music.163.com/api/search/get/web?s=" + query + "&type=1&limit=10&offset=0", "https://music.163.com/");
        // 模拟插件 Gson 逻辑：找歌曲对象（"name" 紧跟后面的顶级 id 字段），取第一个歌曲的 id
        // 歌曲对象特征：顶层有 "name":"海阔天空" 后跟 "id":1357375695
        String pattern = "\"name\":\"[^\"]{1,60}\",\"alias\":\\[\\],\"id\":(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(searchJson);
        String id = m.find() ? m.group(1) : "NONE";
        System.out.println("歌曲 id = " + id);
        // 柠柚解析
        String parseJson = get("https://api.nycnm.cn/api/v2/163music?ids=" + id + "&level=standard&type=json&apikey=" + key, null);
        int stIdx = parseJson.indexOf("\"status\":");
        String status = stIdx >= 0 ? parseJson.substring(stIdx + 9, parseJson.indexOf(',', stIdx)).trim() : "?";
        int uIdx = parseJson.indexOf("\"url\":");
        String url = uIdx >= 0 ? parseJson.substring(uIdx + 7, parseJson.indexOf('"', uIdx + 8)) : "NONE";
        System.out.println("柠柚解析: status=" + status + " url长度=" + url.length());
        System.out.println("完整链路 " + (status.equals("200") && url.length() > 100 ? "OK ✅" : "失败 ❌"));
    }
}
