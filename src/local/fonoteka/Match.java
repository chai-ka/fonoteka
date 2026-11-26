package local.fonoteka;

import org.json.*;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Match {
    final String title, artist, album, cover;
    Match(String title, String artist, String album, String cover) {
        this.title = title; this.artist = artist; this.album = album; this.cover = cover;
    }
    String label() { return artist + " — " + title + "\n" + album; }

    static List<Match> search(String title, String artist) throws Exception {
        try {
            List<Match> matches = musicBrainz(title, artist);
            if (!matches.isEmpty()) return matches;
        } catch (IOException unavailable) {
            // Some networks cannot reach MusicBrainz. Try Apple's public song catalog.
        }
        String address = "https://itunes.apple.com/search?entity=song&limit=12&term=" + Uri.encode(title + " " + artist);
        JSONArray songs = new JSONObject(new String(get(address), StandardCharsets.UTF_8)).getJSONArray("results");
        ArrayList<Match> matches = new ArrayList<>();
        for (int i=0; i<songs.length(); i++) {
            JSONObject song = songs.getJSONObject(i);
            matches.add(new Match(song.getString("trackName"), song.optString("artistName"), song.optString("collectionName"),
                song.optString("artworkUrl100")));
        }
        return matches;
    }
    private static List<Match> musicBrainz(String title, String artist) throws Exception {
        String query = "recording:" + quoted(title);
        if (!artist.isEmpty()) query += " AND artist:" + quoted(artist);
        byte[] bytes = get("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=6&query=" + Uri.encode(query));
        JSONArray recordings = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("recordings");
        ArrayList<Match> matches = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i=0; i<recordings.length(); i++) {
            JSONObject item = recordings.getJSONObject(i);
            JSONArray credits = item.getJSONArray("artist-credit");
            StringBuilder names = new StringBuilder();
            for (int j=0; j<credits.length(); j++) {
                JSONObject credit = credits.getJSONObject(j);
                names.append(credit.optString("name")).append(credit.optString("joinphrase"));
            }
            JSONArray releases = item.optJSONArray("releases");
            if (releases == null || releases.length()==0)
                matches.add(new Match(item.getString("title"), names.toString(), "", ""));
            for (int j=0; releases!=null && j<Math.min(4,releases.length()); j++) {
                JSONObject release = releases.getJSONObject(j);
                if (seen.add(item.getString("id")+release.getString("id")))
                    matches.add(new Match(item.getString("title"), names.toString(), release.getString("title"),
                        "https://coverartarchive.org/release/" + UUID.fromString(release.getString("id")) + "/front-250"));
            }
        }
        return matches;
    }
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    static Match recognize(String token, byte[] sample) throws Exception {
        HttpURLConnection connection = connect("https://api.audd.io/");
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String encoded = android.util.Base64.encodeToString(sample, android.util.Base64.NO_WRAP);
            byte[] body = ("api_token="+Uri.encode(token)+"&return=apple_music&audio="+Uri.encode(encoded)).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            JSONObject response = new JSONObject(new String(response(connection), StandardCharsets.UTF_8));
            if (!"success".equals(response.optString("status"))) throw new IOException("AudD отклонил запрос. Проверьте ключ и лимит.");
            JSONObject result = response.optJSONObject("result");
            if (result == null) throw new IOException("AudD не узнал запись. Попробуйте поиск по названию.");
            JSONObject apple = result.optJSONObject("apple_music");
            JSONObject art = apple == null ? null : apple.optJSONObject("artwork");
            return new Match(result.getString("title"), result.optString("artist"), result.optString("album"),
                art == null ? "" : art.optString("url").replace("{w}", "300").replace("{h}", "300"));
        } finally { connection.disconnect(); }
    }
    static Match identify(byte[] wav) throws Exception {
        byte[] fingerprint = new Fingerprint().encode(wav);
        if (fingerprint.length == 56) return null;
        try {
            return lookupFingerprint(fingerprint, wav);
        } catch (java.net.SocketTimeoutException firstTimeout) {
            return lookupFingerprint(fingerprint, wav);
        }
    }

    private static Match lookupFingerprint(byte[] fingerprint, byte[] wav) throws Exception {
        long now = System.currentTimeMillis();
        int rate = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24);
        JSONObject signature = new JSONObject().put("uri", "data:audio/vnd.shazam.sig;base64," +
            android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP))
            .put("samplems", (wav.length - 44) * 500L / rate).put("timestamp", now);
        JSONObject body = new JSONObject().put("signature", signature).put("timestamp", now).put("timezone", "UTC");
        String url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" + UUID.randomUUID() + "/" +
            UUID.randomUUID() + "?sync=true&webv3=true&sampling=true";
        HttpURLConnection c = connect(url);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            JSONObject result = new JSONObject(new String(response(c), StandardCharsets.UTF_8));
            JSONObject track = result.optJSONObject("track");
            if (track == null) return null;
            String album = "";
            JSONArray sections = track.optJSONArray("sections");
            for (int i=0; sections!=null && i<sections.length(); i++) {
                JSONArray metadata = sections.getJSONObject(i).optJSONArray("metadata");
                for (int j=0; metadata!=null && j<metadata.length(); j++) {
                    JSONObject field = metadata.getJSONObject(j);
                    if ("Album".equalsIgnoreCase(field.optString("title"))) album = field.optString("text");
                }
            }
            JSONObject images = track.optJSONObject("images");
            return new Match(track.getString("title"), track.getString("subtitle"), album,
                images == null ? "" : images.optString("coverart"));
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection connect(String address) throws IOException {
        URL url = new URL(address);
        if (!url.getProtocol().equals("https")) throw new IOException("Требуется HTTPS");
        HttpURLConnection c = (HttpURLConnection)url.openConnection();
        c.setRequestProperty("User-Agent", "Fonoteka/1.0 (https://github.com/chai-ka/fonoteka)");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        return c;
    }
    static byte[] get(String address) throws IOException {
        HttpURLConnection c = connect(address);
        try { return response(c); } finally { c.disconnect(); }
    }
    private static byte[] response(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Сервис вернул HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bytes.size() + n > 3*1024*1024) throw new IOException("Ответ слишком большой");
                bytes.write(buf, 0, n);
            }
            return bytes.toByteArray();
        }
    }
}
