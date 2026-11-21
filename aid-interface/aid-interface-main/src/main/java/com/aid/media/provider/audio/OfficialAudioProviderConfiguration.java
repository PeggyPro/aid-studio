package com.aid.media.provider.audio;

import com.aid.media.provider.AudioProviderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Set;

@Configuration
public class OfficialAudioProviderConfiguration {
    @Bean public AudioProviderClient murekaAudioProvider(OfficialAudioProtocolSupport support){return new OfficialAudioProviderClient("mureka","mureka-audio",Set.of("mureka"),Set.of("mureka-9.5","mureka-o2","mureka-8"),support);}
    @Bean public AudioProviderClient seedAudioProvider(OfficialAudioProtocolSupport support){return new OfficialAudioProviderClient("seed","seed-audio",Set.of("volcengine_seed_audio"),Set.of("seed-audio-1.0"),support);}
    @Bean public AudioProviderClient elevenLabsAudioProvider(OfficialAudioProtocolSupport support){return new OfficialAudioProviderClient("elevenlabs","elevenlabs-audio",Set.of("elevenlabs"),Set.of("eleven_v4","eleven_v4_turbo","music_v2_5"),support);}
    @Bean public AudioProviderClient soniloAudioProvider(OfficialAudioProtocolSupport support){return new OfficialAudioProviderClient("sonilo","sonilo-audio",Set.of("sonilo"),Set.of(),support);}
}
