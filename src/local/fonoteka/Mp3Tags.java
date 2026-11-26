package local.fonoteka;

import com.mpatric.mp3agic.*;
import java.io.*;

final class Mp3Tags {
    static void write(File cache, InputStream in, OutputStream out, Match match, byte[] image) throws Exception {
        File input = File.createTempFile("read-", ".mp3", cache);
        try {
            try (OutputStream temp = new FileOutputStream(input)) { MainActivity.copy(in,temp); }
            Mp3File song = new Mp3File(input.getPath());
            ID3v2 tags = song.hasId3v2Tag() ? song.getId3v2Tag() : new ID3v24Tag();
            tags.setTitle(match.title);
            tags.setArtist(match.artist);
            if (!match.album.isEmpty()) tags.setAlbum(match.album);
            if (image != null) tags.setAlbumImage(image, "image/jpeg");
            song.setId3v2Tag(tags);
            File output = File.createTempFile("write-", ".mp3", cache);
            try {
                song.save(output.getPath());
                try (InputStream temp = new FileInputStream(output)) { MainActivity.copy(temp,out); }
            } finally { output.delete(); }
        } finally { input.delete(); }
    }
}
