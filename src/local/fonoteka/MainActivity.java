package local.fonoteka;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final LinkedHashSet<Track> selected = new LinkedHashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri library;
    private LinearLayout rows;
    private TextView status;
    private EditText search;
    private boolean busy;
    private ArrayList<Uri> pending = new ArrayList<>();
    private static final int FOLDER = 1, AUDIO = 2, EXPORT = 3;

    private static final class Track {
        String uri, title, artist, album, mime;
        Track(String uri, String title, String artist, String album, String mime) {
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.mime = mime;
        }
        String label() { return artist.isEmpty() ? title : artist + " — " + title; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getPreferences(0).getString("folder", "");
        if (!path.isEmpty()) library = Uri.parse(path);
        try (FileInputStream input = openFileInput("tracks.json")) {
            JSONArray data = new JSONArray(readText(input));
            for (int i = 0; i < data.length(); i++) {
                JSONArray track = data.getJSONArray(i);
                tracks.add(new Track(track.getString(0), track.getString(1), track.getString(2), track.getString(3), track.getString(4)));
            }
        } catch (FileNotFoundException firstRun) {
            // The catalogue is created on the first successful import.
        } catch (Exception error) { message("Не удалось прочитать каталог"); }

        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        LinearLayout content = column();
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(18), dp(12), dp(18), dp(12));
        content.setBackgroundColor(INK);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("Фонотека", 27);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(brand, weight());
        header.addView(button("Папка", this::pickFolder));
        content.addView(header);

        FrameLayout poster = new FrameLayout(this);
        poster.setBackground(shape(CORAL, 20));
        poster.setClipToOutline(true);
        poster.addView(new Vinyl(this));
        LinearLayout caption = column();
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(dp(20), 0, 0, 0);
        TextView heading = label("Моя\nмузыка", 32);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        heading.setIncludeFontPadding(false);
        caption.addView(heading);
        Button add = button("＋  Добавить", this::chooseSource);
        caption.addView(add, new LinearLayout.LayoutParams(-2, dp(46)));
        poster.addView(caption);
        LinearLayout.LayoutParams posterSize = new LinearLayout.LayoutParams(-1, dp(getResources().getConfiguration().screenHeightDp < 500 ? 110 : 158));
        posterSize.setMargins(0, dp(10), 0, dp(12));
        content.addView(poster, posterSize);

        search = new EditText(this);
        search.setSingleLine();
        search.setTextSize(16);
        search.setHint("Поиск по трекам и артистам");
        search.setBackground(shape(PANEL, 14));
        search.setPadding(dp(16), 0, dp(12), 0);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { refresh(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        content.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout section = new LinearLayout(this);
        section.setPadding(0, dp(14), 0, dp(8));
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(label("Треки", 21), weight());
        status = label("", 13);
        section.addView(status);
        content.addView(section);
        ScrollView scroll = new ScrollView(this);
        rows = column();
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        normalizeButton = button("Нормализовать", this::normalizeSelected);
        content.addView(normalizeButton);
        exportButton = button("Экспорт выбранных в ZIP", this::chooseExport);
        content.addView(exportButton);
        playerBar = new LinearLayout(this);
        playerBar.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.setPadding(dp(12), dp(4), dp(4), dp(4));
        playerBar.setBackground(shape(PANEL, 16));
        nowPlaying = label("", 15);
        nowPlaying.setMaxLines(2);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        playerBar.addView(nowPlaying, weight());
        toggle = button("▶", () -> command("toggle"));
        playerBar.addView(toggle);
        Button next = button("▶▏", () -> command("next"));
        next.setContentDescription("Следующий трек");
        playerBar.addView(next);
        content.addView(playerBar);
        setContentView(content);
        refresh();
        if (state == null) receive(getIntent());
        else {
            ArrayList<Uri> restored = state.getParcelableArrayList("pendingImport");
            if (restored != null) pending = restored;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelableArrayList("pendingImport", pending);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receive(intent);
    }

    private void receive(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Object stream = extras.get(Intent.EXTRA_STREAM);
        List<?> items = Intent.ACTION_SEND_MULTIPLE.equals(action) && stream instanceof List
            ? (List<?>) stream : Collections.singletonList(stream);
        ArrayList<Uri> files = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Uri && ContentResolver.SCHEME_CONTENT.equals(((Uri) item).getScheme())) {
                files.add((Uri) item);
            }
        }
        if (files.isEmpty()) message("Выберите аудиофайлы через системное меню");
        else accept(files);
    }

    private void chooseSource() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, AUDIO);
    }

    private void pickFolder() {
        if (!busy) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), FOLDER);
    }

    private void accept(ArrayList<Uri> files) {
        if (busy) { message("Дождитесь завершения текущей операции"); return; }
        if (library == null) {
            pending = files;
            pickFolder();
            return;
        }
        busy = true;
        status.setText("Импорт музыки…");
        worker.execute(() -> {
            ArrayList<Track> imported = new ArrayList<>();
            int failed = 0;
            for (Uri file : files) {
                try { imported.add(importTrack(file)); }
                catch (Exception error) { failed++; }
            }
            int failures = failed;
            runOnUiThread(() -> {
                busy = false;
                tracks.addAll(imported);
                save();
                refresh();
                message("Добавлено: " + imported.size() + " · Ошибок: " + failures);
            });
        });
    }

    private Track importTrack(Uri source) throws Exception {
        String mime = getContentResolver().getType(source);
        if (mime == null) mime = "application/octet-stream";
        String title = "Трек";
        String extension = "";
        try (android.database.Cursor cursor = getContentResolver().query(source,
            new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                title = cursor.getString(0);
                int dot = title.lastIndexOf('.');
                if (dot > 0) { extension = title.substring(dot); title = title.substring(0, dot); }
            }
        }
        String artist = "", album = "";
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(this, source);
            title = tag(metadata, MediaMetadataRetriever.METADATA_KEY_TITLE, title);
            artist = tag(metadata, MediaMetadataRetriever.METADATA_KEY_ARTIST, "");
            album = tag(metadata, MediaMetadataRetriever.METADATA_KEY_ALBUM, "");
        } catch (Exception ignored) {
            // Use the file name when the source has unreadable or missing metadata.
        } finally {
            metadata.release();
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(library, DocumentsContract.getTreeDocumentId(library));
        Uri folder = directory(directory(root, artist.isEmpty() ? "Неизвестный исполнитель" : safe(artist)),
            album.isEmpty() ? "Без альбома" : safe(album));
        Uri output = DocumentsContract.createDocument(getContentResolver(), folder, mime, safe(title) + extension);
        if (output == null) throw new IOException("Could not create audio file");
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream stream = getContentResolver().openOutputStream(output)) {
            if (input == null || stream == null) throw new IOException("Could not open audio file");
            copy(input, stream);
        } catch (Exception failure) {
            try { DocumentsContract.deleteDocument(getContentResolver(), output); }
            catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        return new Track(output.toString(), title, artist, album, mime);
    }

    private Uri directory(Uri parent, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        try (android.database.Cursor cursor = getContentResolver().query(children,
            new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (name.equals(cursor.getString(1)) && Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0));
                }
            }
        }
        Uri created = DocumentsContract.createDocument(getContentResolver(), parent, Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("Could not create music directory");
        return created;
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static String tag(MediaMetadataRetriever metadata, int key, String fallback) {
        String value = metadata.extractMetadata(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safe(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim().replaceAll("[. ]+$", "");
        return name.isEmpty() ? "_" : name;
    }

    private void chooseExport() {
        if (busy) return;
        if (selected.isEmpty()) { message("Отметьте треки для экспорта"); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Плейлист.zip"), EXPORT);
    }

    private void export(Uri output) {
        ArrayList<Track> playlist = new ArrayList<>(selected);
        busy = true;
        status.setText("Экспорт плейлиста…");
        worker.execute(() -> {
            String result = "Плейлист сохранён";
            try (OutputStream stream = getContentResolver().openOutputStream(output, "wt");
                 ZipOutputStream zip = new ZipOutputStream(Objects.requireNonNull(stream))) {
                StringBuilder m3u = new StringBuilder("#EXTM3U\n");
                int number = 1;
                for (Track track : playlist) {
                    String name = null;
                    Uri source = Uri.parse(track.uri);
                    try (android.database.Cursor cursor = getContentResolver().query(source,
                        new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    String entry = String.format(Locale.ROOT, "Audio/%03d - %s", number++, safe(name == null ? track.title : name));
                    zip.putNextEntry(new ZipEntry(entry));
                    try (InputStream input = getContentResolver().openInputStream(source)) {
                        if (input == null) throw new IOException("Audio file unavailable");
                        copy(input, zip);
                    }
                    zip.closeEntry();
                    m3u.append(entry).append('\n');
                }
                zip.putNextEntry(new ZipEntry("Playlist.m3u8"));
                zip.write(m3u.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } catch (Exception error) {
                result = "Экспорт не завершён";
                try { DocumentsContract.deleteDocument(getContentResolver(), output); }
                catch (Exception ignored) {}
            }
            String summary = result;
            runOnUiThread(() -> { busy = false; refresh(); message(summary); });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null) {
            if (request == FOLDER) pending.clear();
            return;
        }
        if (request == FOLDER) {
            try {
                Uri chosen = intent.getData();
                getContentResolver().takePersistableUriPermission(chosen,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                library = chosen;
                getPreferences(0).edit().putString("folder", chosen.toString()).apply();
                ArrayList<Uri> files = pending;
                pending = new ArrayList<>();
                if (!files.isEmpty()) accept(files); else refresh();
            } catch (Exception error) { message("Не удалось открыть папку"); }
        } else if (request == AUDIO) {
            ArrayList<Uri> files = new ArrayList<>();
            ClipData clips = intent.getClipData();
            if (clips != null) {
                for (int i = 0; i < clips.getItemCount(); i++) files.add(clips.getItemAt(i).getUri());
            } else if (intent.getData() != null) files.add(intent.getData());
            if (!files.isEmpty()) accept(files);
        } else if (request == EXPORT && intent.getData() != null) export(intent.getData());
    }

    private void refresh() {
        selectionChanged();
        rows.removeAllViews();
        String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<Track> visible = new ArrayList<>();
        for (Track track : tracks) {
            if ((track.label() + " " + track.album).toLowerCase(Locale.ROOT).contains(term)) visible.add(track);
        }
        if (visible.isEmpty()) {
            TextView empty = label(tracks.isEmpty() ? "Коллекция пока пуста" : "Совпадений нет", 19);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, dp(6));
            rows.addView(empty);
            TextView help = label(tracks.isEmpty() ? "Нажмите «Добавить» и выберите аудиофайлы." : "Попробуйте другое название или имя артиста.", 14);
            help.setTextColor(0xffbcbab5);
            help.setGravity(Gravity.CENTER);
            rows.addView(help);
        }
        for (Track track : visible) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Vinyl cover = new Vinyl(this);
            cover.setBackground(shape(CORAL, 8));
            cover.setClipToOutline(true);
            cover.setOnClickListener(v -> play(visible, track));
            View artwork = cover;
            File thumb = thumbnail(track.uri);
            if (thumb.isFile()) {
                ImageView image = new ImageView(this);
                image.setImageURI(Uri.fromFile(thumb));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setOnClickListener(v -> play(visible, track));
                artwork = image;
            }
            row.addView(artwork, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout info = column();
            info.setPadding(dp(8), dp(10), dp(8), dp(10));
            info.addView(label(track.title, 18));
            info.addView(label(String.join(" · ", Arrays.asList(track.artist, track.album)).replaceAll("^ · | · $", ""), 13));
            info.setOnClickListener(view -> play(visible, track));
            info.setOnLongClickListener(view -> { edit(track); return true; });
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox check = new CheckBox(this);
            check.setContentDescription("Выбрать " + track.title);
            check.setChecked(selected.contains(track));
            check.setOnCheckedChangeListener((view, checked) -> {
                if (checked) selected.add(track); else selected.remove(track);
                selectionChanged();
            });
            Button tags = button("⋯", () -> edit(track));
            tags.setContentDescription("Теги и распознавание: " + track.title);
            row.addView(tags);
            row.addView(check);
            rows.addView(row);
        }
    }

    private void edit(Track track) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText title = field(form, "Название", track.title);
        EditText artist = field(form, "Исполнитель", track.artist);
        EditText album = field(form, "Альбом", track.album);
        boolean flac = track.mime.toLowerCase(Locale.ROOT).contains("flac") || track.uri.toLowerCase(Locale.ROOT).endsWith(".flac");
        final AlertDialog[] editor = new AlertDialog[1];
        if (flac) {
            form.addView(button("Найти по названию", () -> {
                String query = title.getText().toString().trim();
                String performer = artist.getText().toString().trim();
                if (query.isEmpty()) { message("Введите название для поиска"); return; }
                editor[0].dismiss();
                background("Поиск в каталогах…", () -> Match.search(query, performer), results -> {
                    if (results.isEmpty()) { message("Совпадений нет. Уточните название."); return; }
                    String[] labels = new String[results.size()];
                    for (int i=0; i<labels.length; i++) labels[i] = results.get(i).label();
                    new AlertDialog.Builder(this).setTitle("Выберите издание").setItems(labels,
                        (d, i) -> preview(track, results.get(i))).setNegativeButton("Отмена", null).show();
                });
            }));
            form.addView(button("Распознать по звуку", () -> { editor[0].dismiss(); recognize(track); }));
        }
        editor[0] = new AlertDialog.Builder(this).setTitle(flac ? "Нормализация FLAC" : "Теги в фонотеке").setView(form)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (dialog, which) -> {
                String name = title.getText().toString().trim();
                if (name.isEmpty()) { message("Введите название"); return; }
                if (flac) {
                    preview(track, new Match(name, artist.getText().toString().trim(), album.getText().toString().trim(), ""));
                    return;
                }
                track.title = name;
                track.artist = artist.getText().toString().trim();
                track.album = album.getText().toString().trim();
                save();
                refresh();
            }).show();
    }

    private interface Result<T> { void accept(T result); }
    private <T> void background(String progress, java.util.concurrent.Callable<T> task, Result<T> success) {
        if (busy) { message("Дождитесь завершения операции"); return; }
        busy = true;
        status.setText(progress);
        worker.execute(() -> {
            try {
                T result = task.call();
                runOnUiThread(() -> {
                    busy = false;
                    if (isDestroyed() || isFinishing()) return;
                    refresh();
                    success.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    if (isDestroyed() || isFinishing()) return;
                    refresh();
                    message("Не удалось завершить: " + error.getLocalizedMessage());
                });
            }
        });
    }

    private void recognize(Track track) {
        EditText key = new EditText(this);
        key.setSingleLine();
        key.setHint("API-ключ AudD");
        key.setInputType(129);
        new AlertDialog.Builder(this).setTitle("Распознавание AudD")
            .setMessage("Отправить до 10 секунд звука в AudD? Потребуется ваш ключ сервиса.")
            .setView(key).setNegativeButton("Отмена", null).setPositiveButton("Распознать", (d, w) -> {
                String token = key.getText().toString().trim();
                if (token.isEmpty()) { message("Введите ключ AudD или выберите поиск по названию"); return; }
                background("Распознавание…", () -> Match.recognize(token,
                    AudioSample.wav(this, Uri.parse(track.uri))), match -> preview(track, match));
            }).show();
    }

    private void preview(Track track, Match match) {
        background("Подготовка сравнения…", () -> loadCover(match), cover -> {
            LinearLayout details = column();
            details.setPadding(dp(20), dp(8), dp(20), dp(8));
            details.addView(label("Было", 14));
            details.addView(label(track.label() + "\n" + track.album, 17));
            details.addView(label("Стало", 14));
            details.addView(label(match.label(), 17));
            if (cover != null) {
                ImageView art = new ImageView(this);
                art.setImageBitmap(cover);
                art.setContentDescription("Обложка выбранного издания");
                details.addView(art, new LinearLayout.LayoutParams(-1, dp(120)));
            } else details.addView(label("Текущая обложка будет сохранена", 14));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(details);
            new AlertDialog.Builder(this).setTitle("Изменения в файле FLAC").setView(scroll)
                .setNegativeButton("Отмена", null).setPositiveButton("Записать", (d, w) -> applyMatch(track, match, cover)).show();
        });
    }

    private Bitmap loadCover(Match match) {
            if (match.cover.isEmpty()) return (Bitmap)null;
            try {
                byte[] data = Match.get(match.cover);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 2048 || bounds.outHeight > 2048) return null;
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (IOException unavailable) { return null; }
    }

    private File thumbnail(String uri) {
        return new File(getFilesDir(), UUID.nameUUIDFromBytes(uri.getBytes(StandardCharsets.UTF_8)) + ".jpg");
    }

    private void applyMatch(Track track, Match match, Bitmap cover) {
        Uri old = Uri.parse(track.uri);
        background("Запись тегов…", () -> writeMatch(track, match, cover), dest -> {
            if (commitMatch(track, match, old, dest)) message("Теги записаны");
            refresh();
        });
    }

    private Uri writeMatch(Track track, Match match, Bitmap cover) throws Exception {
        Uri old = Uri.parse(track.uri);
            Uri root = DocumentsContract.buildDocumentUriUsingTree(library, DocumentsContract.getTreeDocumentId(library));
            Uri artist = directory(root, safe(match.artist.isEmpty() ? "Неизвестный исполнитель" : match.artist));
            Uri album = directory(artist, safe(match.album.isEmpty() ? "Без альбома" : match.album));
            byte[] image = null;
            if (cover != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!cover.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) throw new IOException("Ошибка записи обложки");
                image = bytes.toByteArray();
            }
            boolean mp3 = isMp3(track);
            Uri dest = DocumentsContract.createDocument(getContentResolver(), album, mp3 ? "audio/mpeg" : "audio/flac", safe(match.title) + (mp3 ? ".mp3" : ".flac"));
            if (dest == null || dest.equals(old)) throw new IOException("Не удалось создать отдельный файл");
            try (InputStream in = getContentResolver().openInputStream(old);
                 OutputStream out = getContentResolver().openOutputStream(dest)) {
                if (in == null || out == null) throw new IOException("Файл недоступен");
                if (mp3) Mp3Tags.write(getCacheDir(), in, out, match, image);
                else FlacTags.write(in, out, match.title, match.artist, match.album, image,
                    cover == null ? 0 : cover.getWidth(), cover == null ? 0 : cover.getHeight());
            } catch (Exception error) {
                DocumentsContract.deleteDocument(getContentResolver(), dest);
                throw error;
            }
            if (image != null) {
                try (FileOutputStream out = new FileOutputStream(thumbnail(dest.toString()))) { out.write(image); }
                catch (IOException ignored) { /* Artwork remains embedded in the FLAC. */ }
            } else if (thumbnail(old.toString()).isFile()) {
                try (FileInputStream src = new FileInputStream(thumbnail(old.toString()));
                     FileOutputStream dst = new FileOutputStream(thumbnail(dest.toString()))) { copy(src, dst); }
                catch (IOException ignored) {}
            }
            return dest;
    }

    private boolean commitMatch(Track track, Match match, Uri old, Uri dest) {

            String oldTitle = track.title, oldArtist = track.artist, oldAlbum = track.album;
            track.uri = dest.toString();
            track.title = match.title;
            track.artist = match.artist;
            track.album = match.album;
            if (!save()) {
                track.uri = old.toString();
                track.title = oldTitle; track.artist = oldArtist; track.album = oldAlbum;
                try { DocumentsContract.deleteDocument(getContentResolver(), dest); } catch (Exception ignored) {}
                return false;
            } else {
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), old);
                } catch (Exception ignored) { message("В папке также осталась предыдущая копия"); }
                return true;
            }
    }

    private boolean isMp3(Track track) {
        return "audio/mpeg".equalsIgnoreCase(track.mime) || track.uri.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private void normalizeSelected() {
        if (busy || selected.isEmpty()) return;
        busy = true;
        selectionChanged();
        ArrayList<Track> queue = new ArrayList<>(selected);
        normalizeNext(queue, 0, new ArrayList<>(), 0);
    }

    private void normalizeNext(ArrayList<Track> queue, int index, ArrayList<String> details, int done) {
        if (index == queue.size()) {
            busy = false;
            refresh();
            String summary = "Обработано " + done + " из " + queue.size();
            if (details.isEmpty()) message(summary);
            else new AlertDialog.Builder(this).setTitle(summary).setMessage(android.text.TextUtils.join("\n", details))
                .setPositiveButton("Закрыть", null).show();
            return;
        }
        Track track = queue.get(index);
        Uri old = Uri.parse(track.uri);
        status.setText("Нормализация " + (index+1) + " / " + queue.size());
        worker.execute(() -> {
            try {
                if (!isMp3(track) && !track.mime.toLowerCase(Locale.ROOT).contains("flac")
                    && !track.uri.toLowerCase(Locale.ROOT).endsWith(".flac"))
                    throw new IOException("поддерживаются MP3 и FLAC");
                Match match = Match.identify(AudioSample.wav(this, old));
                if (match == null) throw new IOException("запись не распознана");
                Bitmap cover = loadCover(match);
                Match resolved = match.album.isEmpty() ? new Match(match.title,match.artist,track.album,match.cover) : match;
                Uri dest = writeMatch(track, resolved, cover);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        try { DocumentsContract.deleteDocument(getContentResolver(), dest); } catch (Exception ignored) {}
                        return;
                    }
                    boolean saved = commitMatch(track,resolved,old,dest);
                    if (saved) selected.remove(track);
                    if (!saved) details.add(track.title + " — ошибка сохранения");
                    else if (cover == null) details.add(track.title + " — обложка недоступна");
                    refresh();
                    normalizeNext(queue,index+1,details,done+(saved?1:0));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    details.add(track.title + " — " + error.getLocalizedMessage());
                    normalizeNext(queue,index+1,details,done);
                });
            }
        });
    }

    private EditText field(LinearLayout parent, String hint, String text) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(text);
        input.setSingleLine();
        parent.addView(input);
        return input;
    }

    private boolean save() {
        JSONArray data = new JSONArray();
        for (Track track : tracks) data.put(new JSONArray(Arrays.asList(track.uri, track.title, track.artist, track.album, track.mime)));
        android.util.AtomicFile file = new android.util.AtomicFile(new File(getFilesDir(), "tracks.json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            return true;
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            message("Не удалось сохранить каталог");
            return false;
        }
    }

    private void play(ArrayList<Track> queue, Track current) {
        ArrayList<String> uris = new ArrayList<>(), titles = new ArrayList<>();
        for (Track track : queue) { uris.add(track.uri); titles.add(track.label()); }
        startForegroundService(new Intent(this, Playback.class).setAction("play")
            .putStringArrayListExtra("uris", uris).putStringArrayListExtra("titles", titles).putExtra("index", queue.indexOf(current)));
    }

    private static final int INK = 0xff191918, PANEL = 0xff30302e, CORAL = 0xfff29b78;
    private Button exportButton, normalizeButton, toggle;
    private TextView nowPlaying;
    private LinearLayout playerBar;
    private final Runnable playbackChanges = this::updatePlayer;
    @Override protected void onStart() {
        super.onStart();
        Playback.listeners.add(playbackChanges);
        updatePlayer();
    }
    @Override protected void onStop() {
        Playback.listeners.remove(playbackChanges);
        super.onStop();
    }
    private void updatePlayer() {
        playerBar.setVisibility(Playback.currentTitle == null ? View.GONE : View.VISIBLE);
        nowPlaying.setText(Playback.currentTitle);
        toggle.setText(Playback.playing ? "Ⅱ" : "▶");
        toggle.setContentDescription(Playback.playing ? "Пауза" : "Продолжить");
    }
    private void selectionChanged() {
        if (!busy) status.setText(selected.isEmpty() ? "Всего  " + tracks.size() : "Выбрано  " + selected.size());
        normalizeButton.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        normalizeButton.setEnabled(!busy);
        exportButton.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private static final class Vinyl extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Vinyl(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float radius = Math.min(getHeight() * .82f, getWidth() * .48f);
            boolean small = getWidth() <= getHeight();
            float x = small ? getWidth() / 2f : getWidth() - radius * .43f;
            float y = getHeight() * .48f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(INK);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, radius * .008f));
            paint.setColor(0xff64615b);
            for (int i = 5; i < 15; i++) canvas.drawCircle(x, y, radius * i / 15, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff2d9a6);
            canvas.drawCircle(x, y, radius * .29f, paint);
            paint.setColor(INK);
            canvas.drawCircle(x, y, radius * .048f, paint);
        }
    }
    private void command(String action) { startService(new Intent(this, Playback.class).setAction(action)); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(222, 239, 232));
        view.setTextSize(size);
        return view;
    }
    private Button button(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setBackground(shape(PANEL, 14));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(view -> click.run());
        return button;
    }
}



