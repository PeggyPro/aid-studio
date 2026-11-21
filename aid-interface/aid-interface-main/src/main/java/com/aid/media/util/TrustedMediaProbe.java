package com.aid.media.util;

import com.aid.common.exception.ServiceException;
import com.aid.compose.config.MpsConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 仅探测已取得的完整本地媒体字节，禁止探测器访问网络或外部播放列表。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrustedMediaProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final MpsConfigManager config;

    public Metadata audio(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new ServiceException("样本大小不支持");
        Path file = null;
        Process process = null;
        try {
            file = Files.createTempFile(com.aid.common.config.AidAppConfig.getTemporaryDirectory(), "aid-media-probe-", ".bin");
            Files.write(file, bytes);
            process = new ProcessBuilder(config.getMpsProperties().getFfprobePath(), "-v", "error",
                    "-protocol_whitelist", "file,pipe", "-select_streams", "a:0", "-show_entries",
                    "stream=codec_name,sample_rate,channels,duration:format=duration,format_name", "-of", "json", file.toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) throw new ServiceException("样本解析失败");
            byte[] output = process.getInputStream().readNBytes(65537);
            if (output.length > 65536) throw new ServiceException("样本解析失败");
            JsonNode root = MAPPER.readTree(output);
            JsonNode stream = root.path("streams").path(0);
            BigDecimal seconds = positiveDuration(stream.path("duration").asText());
            if (seconds == null) seconds = positiveDuration(root.path("format").path("duration").asText());
            if (seconds == null) throw new ServiceException("样本时长不可用");
            long millis = seconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact();
            int sampleRate = Integer.parseInt(stream.path("sample_rate").asText());
            int channels = stream.path("channels").asInt();
            String format = root.path("format").path("format_name").asText();
            if (millis <= 0 || sampleRate <= 0 || channels <= 0 || format.isBlank()) throw new ServiceException("样本解析失败");
            return new Metadata(millis, bytes.length, sampleRate, channels, format, stream.path("codec_name").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("样本解析中断");
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) {
            log.info("媒体样本探测失败: {}", ex.getClass().getSimpleName());
            throw new ServiceException("样本解析失败");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (file != null) {
                try { Files.deleteIfExists(file); }
                catch (Exception ex) { log.warn("媒体探测临时文件待清理: {}", file); }
            }
        }
    }

    private static BigDecimal positiveDuration(String value) {
        try {
            BigDecimal result = new BigDecimal(value);
            return result.signum() > 0 ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Validates complete generated video bytes before a provider success can be settled. */
    public VideoMetadata video(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > 256 * 1024 * 1024)
            throw new InvalidVideoException("视频文件大小不支持");
        Path file = null;
        Process process = null;
        try {
            file = Files.createTempFile(com.aid.common.config.AidAppConfig.getTemporaryDirectory(), "aid-video-probe-", ".mp4");
            Files.write(file, bytes);
            process = new ProcessBuilder(config.getMpsProperties().getFfprobePath(), "-v", "error",
                    "-protocol_whitelist", "file,pipe", "-show_entries",
                    "stream=codec_type,codec_name,width,height,avg_frame_rate,duration:format=duration,format_name",
                    "-of", "json", file.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new ServiceException("视频文件解析超时");
            if (process.exitValue() != 0) throw new InvalidVideoException("视频文件无效");
            byte[] output = process.getInputStream().readNBytes(65537);
            if (output.length > 65536) throw new ServiceException("视频文件解析失败");
            JsonNode root = MAPPER.readTree(output);
            JsonNode video = null;
            boolean audio = false;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText()) && video == null) video = stream;
                audio |= "audio".equals(stream.path("codec_type").asText());
            }
            if (video == null || video.path("width").asInt() <= 0 || video.path("height").asInt() <= 0)
                throw new InvalidVideoException("上游未返回有效视频轨道");
            BigDecimal duration = positiveDuration(root.path("format").path("duration").asText());
            if (duration == null) duration = positiveDuration(video.path("duration").asText());
            if (duration == null) throw new InvalidVideoException("视频时长不可用");
            String[] rate = video.path("avg_frame_rate").asText().split("/");
            BigDecimal fps = rate.length == 2 ? new BigDecimal(rate[0]).divide(new BigDecimal(rate[1]), 6, RoundingMode.HALF_UP)
                    : new BigDecimal(rate[0]);
            if (fps.signum() <= 0 || !root.path("format").path("format_name").asText().contains("mp4"))
                throw new InvalidVideoException("上游视频格式无效");
            return new VideoMetadata(duration.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact(),
                    bytes.length, video.path("width").asInt(), video.path("height").asInt(), fps, audio);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new ServiceException("视频文件解析中断");
        } catch (InvalidVideoException failure) { throw failure;
        } catch (ServiceException failure) { throw failure; }
        catch (Exception failure) { throw new ServiceException("视频文件解析失败"); }
        finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (file != null) try { Files.deleteIfExists(file); }
            catch (Exception failure) { log.warn("视频探测临时文件待清理: {}", file); }
        }
    }

    public record VideoMetadata(long durationMs, long sizeBytes, int width, int height, BigDecimal fps, boolean audio) { }

    public static final class InvalidVideoException extends RuntimeException {
        public InvalidVideoException(String message) { super(message); }
    }

    public record Metadata(long durationMs, long sizeBytes, int sampleRate, int channels, String format, String codec) { }
}
