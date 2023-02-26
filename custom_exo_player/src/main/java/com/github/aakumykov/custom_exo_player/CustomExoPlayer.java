package com.github.aakumykov.custom_exo_player;

import android.content.Context;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.github.aakumykov.enum_utils.EnumUtils;
import com.github.aakumykov.sound_player_api.PlayerState;
import com.github.aakumykov.sound_player_api.SoundItem;
import com.github.aakumykov.sound_player_api.SoundPlayer;
import com.github.aakumykov.sound_player_api.SoundPlayerCallback;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.MediaMetadata;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

// FIXME: слишком сложный, разбить на части...
public class CustomExoPlayer extends Binder implements SoundPlayer {

    private static final String TAG = CustomExoPlayer.class.getSimpleName();

    private final ExoPlayer mExoPlayer;

    private final ExoPlayerListener mExoPlayerListener;

    private final List<SoundPlayerCallback> mCallbackList = new ArrayList<>();

    private final SortedMap<String, SoundItem> mSoundItemMap = new TreeMap<>();

    @NonNull
    private PlayerState mCurrentPlayerState = PlayerState.IDLE;

    @Nullable
    private MutableLiveData<PlayerState> mPlayerStateMutableLiveData;

    @Nullable
    private Throwable mError;

    @Nullable
    private SoundItem mCurrentItem;


    public CustomExoPlayer(@NonNull Context context) {
        mExoPlayer = new ExoPlayer.Builder(context).build();
        mExoPlayer.setPlayWhenReady(false);

        mExoPlayerListener = new ExoPlayerListener();
        mExoPlayer.addListener(mExoPlayerListener);
    }


    @Override
    public void play(@NonNull SoundItem soundFile) {
        play(Collections.singletonList(soundFile));
    }

    @Override
    public void play(List<SoundItem> soundItemList) {
        mExoPlayer.stop();
        mExoPlayer.clearMediaItems();
        mSoundItemMap.clear();

        for (SoundItem soundItem : soundItemList)
            mExoPlayer.addMediaItem(soundItem2mediaItem(soundItem));

        mExoPlayer.prepare();
    }

    @Override
    public void pause() {
        mExoPlayer.pause();
    }

    @Override
    public void resume() {
        mExoPlayer.play();
    }

    @Override
    public void stop() {
        mExoPlayer.stop();
    }

    @Override
    public void skipToNext() {
        if (mExoPlayer.hasNextMediaItem())
            mExoPlayer.seekToNext();
    }

    @Override
    public void skipToPrev() {
        if (mExoPlayer.hasPreviousMediaItem())
            mExoPlayer.seekToPrevious();
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayer.isPlaying();
    }

    @Override
    public boolean isPaused() {
        return PlayerState.PAUSED.equals(mCurrentPlayerState);
    }

    @Override
    public boolean isStopped() {
        return PlayerState.STOPPED.equals(mCurrentPlayerState);
    }


    @Override
    public void addCallback(SoundPlayerCallback callback) {
        mCallbackList.add(callback);
    }

    @Override
    public void removeCallback(SoundPlayerCallback callback) {
        mCallbackList.remove(callback);
    }

    @Override
    public LiveData<PlayerState> getPlayerState() {
        if (null == mPlayerStateMutableLiveData)
            mPlayerStateMutableLiveData = new MutableLiveData<>(mCurrentPlayerState);
        return mPlayerStateMutableLiveData;
    }

    @Nullable @Override
    public Throwable getError() {
        return mError;
    }

    @Override
    public void setError(@NonNull Throwable throwable) {
        mError = throwable;
        publishPlayerState(PlayerState.ERROR);
    }

    @Nullable @Override
    public SoundItem getCurrentItem() {
        return mCurrentItem;
    }

    @NonNull @Override
    public PlayerState getCurrentState() {
        assert null != mCurrentPlayerState;
        return mCurrentPlayerState;
    }

    @Override
    public void release() {
        clearCallbacks();

        mExoPlayer.stop();
        mExoPlayer.removeListener(mExoPlayerListener);
        mExoPlayer.release();
    }

    private void publishPlayerState(@NonNull PlayerState playerState) {
        mCurrentPlayerState = playerState;
        mCurrentItem = findCurrentSoundItem();

        sendStateToCallback();
        sendStateToLiveData();
    }


    private void sendStateToLiveData() {
        if (null != mPlayerStateMutableLiveData)
            mPlayerStateMutableLiveData.postValue(mCurrentPlayerState);
    }

    private void sendStateToCallback() {
        for (SoundPlayerCallback callback : mCallbackList)
            callback.onPlayerStateChanged(mCurrentPlayerState);
    }

    @Nullable
    private SoundItem findCurrentSoundItem() {
        final MediaItem mediaItem = mExoPlayer.getCurrentMediaItem();
        if (null == mediaItem)
            return null;

        final String mediaId = mediaItem.mediaId;
        if (mSoundItemMap.containsKey(mediaId))
            return mSoundItemMap.get(mediaId);
        else
            return null;
    }



    private void clearCallbacks() {
        mCallbackList.clear();
    }


    private MediaItem soundItem2mediaItem(SoundItem soundItem) {

        mSoundItemMap.put(soundItem.getId(), soundItem);

        return new MediaItem.Builder()
                .setMediaId(soundItem.getId())
                .setUri(soundItem.getFileUri())
                .setMediaMetadata(soundItem2mediaMetadata(soundItem))
                .build();
    }

    private MediaMetadata soundItem2mediaMetadata(SoundItem soundItem) {
        return new MediaMetadata.Builder()
                .setTitle(soundItem.getTitle())
                .build();
    }



    private class ExoPlayerListener implements Player.Listener {

        @Override
        public void onPlaybackStateChanged(final int playbackState) {

            switch (playbackState) {
                case Player.STATE_IDLE:
                    processIdleEvent();
                    break;

                case Player.STATE_BUFFERING:
                    publishPlayerState(PlayerState.WAITING);
                    break;

                case Player.STATE_READY:
                    // Запускаю воспроизведение здесь, так как playWhenReady отключено для ExoPlayer-а.
                    // Сигнал "воспроизводится" будет отослан из метода "onIsPlayingChanged"
                    mExoPlayer.play();
                    break;

                case Player.STATE_ENDED:
                    publishPlayerState(PlayerState.STOPPED);
                    break;

                default:
                    Player.Listener.super.onPlaybackStateChanged(playbackState);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying)
                processIsPlayingTrueEvent();
            else
                processIsPlayingFalseEvent();
        }

        @Override
        public void onPlayerErrorChanged(@Nullable PlaybackException error) {
            mError = (null == error) ? new RuntimeException("null") : error;
            publishPlayerState(PlayerState.ERROR);
        }


        // PAUSED --> STOPPED, * --> IDLE
        private void processIdleEvent() {
            mCurrentPlayerState = (PlayerState.PAUSED == mCurrentPlayerState) ? PlayerState.STOPPED : PlayerState.IDLE;
            publishPlayerState(mCurrentPlayerState);
        }

        private void processIsPlayingTrueEvent() {
            switch (mCurrentPlayerState) {
                case IDLE:
                case WAITING:
                case STOPPED:
                case ERROR:
                    mCurrentPlayerState = PlayerState.PLAYING;
                    break;

                case PAUSED:
                    mCurrentPlayerState = PlayerState.RESUMED;
                    break;

                case PLAYING:
                case RESUMED:
                    Log.d(TAG, "Пропускаю "+ mCurrentPlayerState);
                    break;

                default:
                    EnumUtils.throwUnknownValue(mCurrentPlayerState);
            }

            publishPlayerState(mCurrentPlayerState);
        }

        private void processIsPlayingFalseEvent() {
            switch (mCurrentPlayerState) {
                case PLAYING:
                case RESUMED:
                    mCurrentPlayerState = PlayerState.PAUSED;
                    break;

                case WAITING:
                case IDLE:
                    mCurrentPlayerState = PlayerState.STOPPED;
                    break;

                case PAUSED:
                case STOPPED:
                case ERROR:
                    Log.d(TAG, "Игнорирую "+ mCurrentPlayerState);
                    break;

                default:
                    EnumUtils.throwUnknownValue(mCurrentPlayerState);
            }

            publishPlayerState(mCurrentPlayerState);
        }

    }
}
