package local.fonoteka;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decode a short sample locally for an explicitly requested AudD lookup. */
final class AudioSample {
    static byte[] wav(Context context, Uri source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, source, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    format = candidate;
                    extractor.selectTrack(i);
                    break;
                }
            }
            if (format == null) throw new IOException("В файле нет аудио");
            long duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            extractor.seekTo(Math.min(duration / 3, 30000000L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format, null, null, 0);
            codec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eof = false;
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (pcm.size() < rate * 2 * 10) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Декодирование заняло слишком много времени");
                int index = eof ? -1 : codec.dequeueInputBuffer(10000);
                if (index >= 0) {
                    int length = extractor.readSampleData(codec.getInputBuffer(index), 0);
                    eof = length < 0;
                    codec.queueInputBuffer(index, 0, Math.max(0, length), eof ? 0 : extractor.getSampleTime(),
                        eof ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (!eof) extractor.advance();
                }
                index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = codec.getOutputFormat();
                    rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING) && actual.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                        throw new IOException("Декодер не поддерживает 16-битный фрагмент");
                    if (rate < 1 || rate > 192000 || channels < 1 || channels > 32) throw new IOException("Неподдерживаемый формат");
                }
                if (index >= 0) {
                    ByteBuffer bytes = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                    bytes.position(info.offset);
                    bytes.limit(info.offset + info.size);
                    while (bytes.remaining() >= channels * 2 && pcm.size() < rate * 2 * 10) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) sum += bytes.getShort();
                        int sample = sum / channels;
                        pcm.write(sample);
                        pcm.write(sample >> 8);
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (pcm.size() == 0) throw new IOException("Пустой фрагмент");
            ByteBuffer wav = ByteBuffer.allocate(44 + pcm.size()).order(ByteOrder.LITTLE_ENDIAN);
            wav.putInt(0x46464952).putInt(36 + pcm.size()).putInt(0x45564157).putInt(0x20746d66);
            wav.putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2);
            wav.putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(pcm.size()).put(pcm.toByteArray());
            return wav.array();
        } finally {
            if (codec != null) codec.release();
            extractor.release();
        }
    }
}
