package com.fridah.tech;

/**
 * Carrier tunnel configurations (HTTP Custom style):
 *  - connect TCP to a proxy
 *  - inject the payload (with [crlf] [ua] [proxy] [host] placeholders, [split] parts wait for a response)
 *  - a 302 response means the free-net loophole is closed on that proxy
 *  - after a good response (101/200) the same socket carries the SSH handshake (JSch)
 */
public class FridahConfig {

    public static class Part {
        public final String data;
        public final boolean waitResponse;
        public Part(String data, boolean waitResponse) { this.data = data; this.waitResponse = waitResponse; }
    }

    public String carrier;
    public String sshHost;
    public int sshPort;
    public String user;
    public String pass;
    public String[] proxies;
    public Part[] parts;

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-A536E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public static FridahConfig forCarrier(String carrier) {
        FridahConfig c = new FridahConfig();
        c.carrier = carrier;
        if ("airtel".equals(carrier)) {
            c.sshHost = "d16l6nb4do0zu4.cloudfront.net";
            c.sshPort = 443;
            c.user = "danaelssh-Lilies";
            c.pass = "Lilies";
            c.proxies = new String[]{"secure.3gdirectpay.com:80"};
            String pl = "GET wss://d16l6nb4do0zu4.cloudfront.net/ HTTP/1.1[crlf]"
                    + "Host: d16l6nb4do0zu4.cloudfront.net[crlf]"
                    + "Connection: Upgrade[crlf]"
                    + "User-Agent: [ua][crlf]"
                    + "Upgrade: websocket[crlf][crlf]";
            c.parts = new Part[]{new Part(pl, true)};
        } else if ("safaricom".equals(carrier)) {
            c.sshHost = "1.kevinredm99.shop";
            c.sshPort = 80;
            c.user = "grupo";
            c.pass = "xxxx";
            // primary proxy odi.site; on 302 switch to betika remote proxy; a second 302 = free net over
            c.proxies = new String[]{"odi.site:80", "solidicon.ke-gamification-service.betika.com:80"};
            String p1 = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [proxy][crlf][crlf]";
            String p2 = "UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade, keep-alive[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]";
            String p3 = "UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]";
            c.parts = new Part[]{new Part(p1, true), new Part(p2, true), new Part(p3, false)};
        } else {
            return null;
        }
        return c;
    }

    public String render(String tmpl, String proxyHost) {
        String proxy = proxyHost.contains(":") ? proxyHost.substring(0, proxyHost.indexOf(':')) : proxyHost;
        return tmpl
                .replace("[crlf]", "\r\n")
                .replace("[ua]", UA)
                .replace("[proxy]", proxy)
                .replace("[host]", sshHost);
    }

    public String toJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("carrier", carrier);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
