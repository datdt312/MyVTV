package com.sam.video.timeline

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.btbtech.myvtv.databinding.ActivityEditorVideoBinding
import com.sam.video.timeline.MainActivity.Constants

class EditorVideoActivity:  AppCompatActivity() {
    private lateinit var binding: ActivityEditorVideoBinding

    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)


        initPlayer()

        binding.addVideo.setOnClickListener { startGetVideoIntent() }
        binding.addAudio.setOnClickListener { startGetAudioIntent() }
    }

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
    }

    private fun addVideo(video: Video) {
        val mediaItem = MediaItem.Builder()
            .setUri(video.path)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(video.duration)
                    .build()
            )
            .build()
        exoPlayer?.addMediaItem(mediaItem)


        // 添加进度
        binding.videoTimelineView.addVideo(
            0, TrackEnum.VIDEO_TRACK, video.duration, video.path,
            0, video.duration
        )

        videoItems.add(Video())
    }

    private fun addTrack() {

    }
    private fun startGetVideoIntent() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.setDataAndType(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Constants.VIDEO_TYPE
        )
        val chooserIntent = Intent.createChooser(intent, null)
        startActivityForResult(chooserIntent, Constants.REQUEST_VIDEO)
    }

    private fun startGetAudioIntent() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.setDataAndType(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Constants.AUDIO_TYPE
        )
        val chooserIntent = Intent.createChooser(intent, null)
        startActivityForResult(chooserIntent, Constants.REQUEST_AUDIO)
    }
}