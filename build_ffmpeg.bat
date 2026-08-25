@echo off
echo 🚀 Building custom FFmpeg with EVERY decoder...
if not exist "ExoPlayer" git clone https://github.com/google/ExoPlayer.git
cd ExoPlayer
set SCRIPT=extensions\ffmpeg\src\main\jni\ffmpeg-build.sh
copy %SCRIPT% %SCRIPT%.backup
(
echo #!/bin/sh
echo ENABLED_DECODERS="aac ac3 adpcm_4xm adpcm_adx adpcm_afc adpcm_agm adpcm_aica adpcm_argo adpcm_ct adpcm_dtk adpcm_ea adpcm_ea_maxis_xa adpcm_ea_r1 adpcm_ea_r2 adpcm_ea_r3 adpcm_eax adpcm_g722 adpcm_g726 adpcm_g726le adpcm_ima_amv adpcm_ima_apc adpcm_ima_dat4 adpcm_ima_dk3 adpcm_ima_dk4 adpcm_ima_ea_eacs adpcm_ima_ea_sead adpcm_ima_iss adpcm_ima_moflex adpcm_ima_mtf adpcm_ima_oki adpcm_ima_qt adpcm_ima_rad adpcm_ima_smjpeg adpcm_ima_ssi adpcm_ima_ws adpcm_ima_wav adpcm_ms adpcm_mtaf adpcm_psx adpcm_sbpro_2 adpcm_sbpro_3 adpcm_sbpro_4 adpcm_swf adpcm_thp adpcm_thp_le adpcm_vima adpcm_xa adpcm_yamaha alac amrnb amrwb ape atrac1 atrac3 atrac3al atrac3p atrac3pal bink binkaudio_dct binkaudio_rdft cook dca dsd_lsbf dsd_msbf dsd_msbf_planar dsicinaudio dss_sp dts dtshd dv_audio eac3 flac g723_1 g729 gsm gsm_ms h261 h263 h263i h263p h264 h264_v4l2m2m h264_mediacodec h264_qsv h264_cuvid hevc hevc_qsv hevc_cuvid hevc_v4l2m2m huffyuv ilbc indeo2 indeo3 indeo4 indeo5 interplay_dpcm jpegls mjpeg mjpeg_cuvid mlp mp1 mp2 mp3 mp3float mp3on4 mp3on4float mpc7 mpc8 mpeg1video mpeg2video mpeg2_cuvid mpeg4 mpeg4_v4l2m2m mpeg4_cuvid msmpeg4v1 msmpeg4v2 msmpeg4v3 msrle msvideo1 nellymoser opus pcm_alaw pcm_dvd pcm_f16le pcm_f24le pcm_f32be pcm_f32le pcm_f64be pcm_f64le pcm_lxf pcm_mulaw pcm_s16be pcm_s16be_planar pcm_s16le pcm_s16le_planar pcm_s24be pcm_s24daud pcm_s24le pcm_s24le_planar pcm_s32be pcm_s32le pcm_s32le_planar pcm_s8 pcm_s8_planar pcm_u16be pcm_u16le pcm_u24be pcm_u24le pcm_u32be pcm_u32le pcm_u8 qdm2 qdmc ra_144 ra_288 ralf rawvideo realtext r10k r210 rv10 rv20 rv30 rv40 s302m sami sipr smacker smc snow sonic srt subrip subviewer svq1 svq3 truemotion1 truemotion2 truemotion2rt truespeech tta twinvq utvideo v210 v308 v408 v410 vb vble vc1 vc1_v4l2m2m vc1_cuvid vima vmdvideo vorbis vp3 vp5 vp6 vp6a vp6f vp7 vp8 vp8_v4l2m2m vp8_cuvid vp9 vp9_v4l2m2m vp9_cuvid wavpack wmalossless wmapro wmav1 wmav2 wmavoice wmv1 wmv2 wmv3 wmv3_v4l2m2m wmv3_cuvid xan_dpcm xma1 xma2 yop zerocodec zlib zmbv"
) > %SCRIPT%
echo Building AAR (30-60 mins)...
call gradlew :extension-ffmpeg:assembleDebug
set AAR=extensions\ffmpeg\build\outputs\aar\ffmpeg-debug.aar
if exist "%AAR%" (
  mkdir ..\app\libs 2>nul
  copy "%AAR%" ..\app\libs\ffmpeg-custom.aar
  echo ✅ AAR copied to app\libs\ffmpeg-custom.aar
) else (
  echo ❌ Build failed
  exit /b 1
)
cd ..
echo 🎉 Done! Run gradlew assembleDebug to build the final APK.
