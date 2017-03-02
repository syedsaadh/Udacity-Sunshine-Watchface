/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xeda.sunshinewatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchface extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String LOG_TAG = SunshineWatchface.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchface.Engine> mWeakReference;

        public EngineHandler(SunshineWatchface.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchface.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        static final String PREFERENCES = "PREFERENCES";
        static final String KEY_WEATHER = "KEY_WEATHER";
        static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";
        static final String WEATHER_DATA_PATH = "/WEATHER_DATA_PATH";
        static final String WEATHER_DATA_ID = "WEATHER_DATA_ID";
        static final String WEATHER_DATA_HIGH = "WEATHER_DATA_HIGH";
        static final String WEATHER_DATA_LOW = "WEATHER_DATA_LOW";

        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mTimePaint;
        Paint mIconPaint;

        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        @Nullable
        Bitmap mBitmap;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient mGoogleApiClient;

        @Nullable
        String mWeather;
        private int mWeatherId;
        private Paint mTempPaint;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
            mWeather = preferences.getString(KEY_WEATHER, "");
            mWeatherId = preferences.getInt(KEY_WEATHER_ID, 0);
            loadIconForWeatherId();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchface.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .build();


            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchface.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchface.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint.setAntiAlias(true);

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mIconPaint = new Paint();
            mTempPaint = new Paint();
            mTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "Weather data has been changed!");
            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (WEATHER_DATA_PATH.equals(item.getUri().getPath())) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    double high = dataMap.getDouble(WEATHER_DATA_HIGH);
                    double low = dataMap.getDouble(WEATHER_DATA_LOW);
                    long id = dataMap.getLong(WEATHER_DATA_ID);

                    mWeather = (int) Math.round(high)+  " \u2103" + "/" + (int) Math.round(low) + " \u2103";
                    mWeatherId = (int) id;

//                    loadIconForWeatherId();

                    Log.d(LOG_TAG, "mWeather " + mWeather);

                    SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(KEY_WEATHER, mWeather);
                    editor.putInt(KEY_WEATHER_ID, mWeatherId);
                    editor.apply();
                    invalidate();
                }
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended");
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchface.this.registerReceiver(mTimeZoneReceiver, filter);
            mGoogleApiClient.connect();
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchface.this.unregisterReceiver(mTimeZoneReceiver);
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchface.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float timeSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(timeSize);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);

            mDatePaint.setTextSize(dateTextSize);
            float temperatureTextSize = resources.getDimension(isRound
                    ? R.dimen.temperature_text_size_round : R.dimen.temperature_text_size);
            mTempPaint.setTextSize(temperatureTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
//                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
//                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);


            String time = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));

            float xPosTime = canvas.getWidth() / 2 - mTimePaint.measureText(time, 0, time.length()) / 2;
            canvas.drawText(time, xPosTime, mYOffset, mTimePaint);
            if (!isInAmbientMode()) {
                // Current date
                int padding = 16;

                String date = String.format("%s, %s %02d %d",
                        mCalendar.getDisplayName(Calendar.DAY_OF_WEEK,Calendar.SHORT, Locale.getDefault()),
                        mCalendar.getDisplayName(Calendar.MONTH,Calendar.SHORT, Locale.getDefault()),
                        mCalendar.get(Calendar.DATE),
                        mCalendar.get(Calendar.YEAR));
                float yPosDate = mYOffset + mDatePaint.getTextSize() + padding;
                float xPosDate = canvas.getWidth() / 2 - mDatePaint.measureText(date, 0, date.length()) / 2;
                canvas.drawText(date, xPosDate, yPosDate, mDatePaint);

                if (mWeatherId != 0 && mBitmap != null) {
                    // Weather Icon
                    float yPosIcon = yPosDate + padding;
                    float xPosIcon = canvas.getWidth() / 2 - mBitmap.getWidth();
                    canvas.drawBitmap(mBitmap, xPosIcon, yPosIcon, mIconPaint);

                    // Temperature
                    float yPosWeather = yPosDate + mTempPaint.getTextSize() + mBitmap.getHeight() / 2;
                    float xPosWeather = canvas.getWidth() / 2;
                    canvas.drawText(mWeather != null ? mWeather : "", xPosWeather, yPosWeather, mTempPaint);
                }
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private void loadIconForWeatherId() {

            int iconId = 0;
            if (mWeatherId >= 200 && mWeatherId <= 232) {
                iconId = R.drawable.ic_storm;
            } else if (mWeatherId >= 300 && mWeatherId <= 321) {
                iconId = R.drawable.ic_light_rain;
            } else if (mWeatherId >= 500 && mWeatherId <= 504) {
                iconId = R.drawable.ic_rain;
            } else if (mWeatherId == 511) {
                iconId = R.drawable.ic_snow;
            } else if (mWeatherId >= 520 && mWeatherId <= 531) {
                iconId = R.drawable.ic_rain;
            } else if (mWeatherId >= 600 && mWeatherId <= 622) {
                iconId = R.drawable.ic_snow;
            } else if (mWeatherId >= 701 && mWeatherId <= 761) {
                iconId = R.drawable.ic_fog;
            } else if (mWeatherId == 761 || mWeatherId == 781) {
                iconId = R.drawable.ic_storm;
            } else if (mWeatherId == 800) {
                iconId = R.drawable.ic_clear;
            } else if (mWeatherId == 801) {
                iconId = R.drawable.ic_light_clouds;
            } else if (mWeatherId >= 802 && mWeatherId <= 804) {
                iconId = R.drawable.ic_cloudy;
            }

            if (iconId != 0) {
                mBitmap = BitmapFactory.decodeResource(getResources(), iconId);
                float sizeY = (float) mBitmap.getHeight();
                float sizeX = (float) mBitmap.getWidth();
                mBitmap = Bitmap.createScaledBitmap(mBitmap, (int) sizeX, (int) sizeY, false);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
