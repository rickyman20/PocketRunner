package com.foxtailgames.pocketrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;

import com.foxtailgames.pocketrunner.databases.Run;
import com.foxtailgames.pocketrunner.databases.RunReaderDbHelper;

import java.util.Date;
import java.util.LinkedList;

import gr.antoniom.chronometer.PreciseChronometer;

/**
 * Class in charge of managing the data from a single run. Has methods for getting strings to print,
 * saving and reading data from the run, counting time, triggering the alarm, etc.
 * @author Ricardo Delfin Garcia
 * @version 1.0
 */
public class RunManager {

    protected double lapLength;
    protected String units;
    protected int lapCount;
    protected LinkedList<Long> lapTimes;
    protected long timeLastLap;

    protected boolean useDistanceForAlarm;
    protected double distanceForAlarm;

    protected Time endTime;
    protected PreciseChronometer chronometer;
    protected long timeChronoStopped;

    protected boolean running, started, done;


    protected RunActivity activity;
    protected Context context;

    protected boolean alarmTriggered;
    protected AlertDialog alarmDialog;
    protected Vibrator vibrator;

    protected RunReaderDbHelper dbHelper;

    protected PebbleManager pebbleManager;

    public RunManager(RunActivity activity, Context context, PreciseChronometer chronometer) {
        updatePreferences(context);
        this.chronometer = chronometer;
        this.timeChronoStopped = 0;
        this.running = false;
        this.started = false;
        this.lapCount = 0;
        this.timeLastLap = 0;
        this.activity = activity;
        this.context = context;
        this.alarmDialog = null;
        this.vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
        this.alarmTriggered = false;
        this.lapTimes = new LinkedList<>();
        this.dbHelper = new RunReaderDbHelper(context);
        this.done = false;
        this.pebbleManager = new PebbleManager(context, lapLength, units, useDistanceForAlarm, distanceForAlarm, endTime);

        //Handler for the chronometer
        this.chronometer.setOnChronometerTickListener(new PreciseChronometer.OnChronometerTickListener() {
            @Override
            public void onChronometerTick(PreciseChronometer preciseChronometer) {
                if(!useDistanceForAlarm && endTime.lessThanOrEqual(RunManager.this.chronometer.getTime())) {
                    triggerAlarm();
                }
            }
        });
    }

    public void updatePreferences(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.lapLength = Double.parseDouble(sharedPreferences.getString(context.getString(R.string.lap_length_input_key), "0"));
        this.units = sharedPreferences.getString(context.getString(R.string.units_list_key), "");
        this.useDistanceForAlarm = sharedPreferences.getBoolean(context.getString(R.string.use_distance_key), false);
        this.distanceForAlarm = Double.parseDouble(sharedPreferences.getString(context.getString(R.string.distance_for_alarm_key), "0"));

        int timeHours = Integer.parseInt(sharedPreferences.getString(context.getString(R.string.time_hours_for_alarm_key), "0"));
        int timeMinutes = Integer.parseInt(sharedPreferences.getString(context.getString(R.string.time_minutes_for_alarm_key), "0"));
        int timeSeconds = Integer.parseInt(sharedPreferences.getString(context.getString(R.string.time_seconds_for_alarm_key), "0"));

        this.endTime = new Time(timeHours, timeMinutes, timeSeconds);
    }

    public void start() {
        chronometer.setBase(SystemClock.elapsedRealtime() + timeChronoStopped);
        chronometer.start();
    }

    public void stop() {
        timeChronoStopped = chronometer.getBase() - SystemClock.elapsedRealtime();
        chronometer.stop();
    }

    public void reset() {
        timeChronoStopped = 0;
    }

    public void stopClicked() {
        //If the person is running, button should say Stop! We logically stop and set running false
        if(running && started) {
            stop();
            running = false;
        }
        //Otherwise, we should start the clock. If !started, then we should reset too and set started appropriately
        else {
            if(!started) {
                started = true;
                reset();
            }

            start();
            running = true;
        }
    }

    public void lapClicked() {
        /*
         * If the person is running, this button functions as a lap. Also, if this is done, new lap
         * should be counted.
         */
        lapCount++;
        lapTimes.addLast(timeLastLap - chronometer.getTimeElapsed());
        timeLastLap = chronometer.getTimeElapsed();
        if (distanceRun() >= distanceForAlarm && useDistanceForAlarm)
            triggerAlarm();

        //If this is a done button, add the run to the database and quit
        if(!running || !started) {
            long[] arr = new long[lapTimes.size()];

            int i = 0;
            for(long time : lapTimes) {
                arr[i] = time;
                i++;
            }

            //Only save if the chronometer has actually been used
            if(chronometer.getTimeElapsed() > 0) {
                Run run = new Run(new Date(), distanceRun(), units, new Time(timeLastLap), arr);
                dbHelper.addRun(run);
                dbHelper.close();
            }

            done = true;
        }
    }

    private double distanceRun() { return lapCount * lapLength; }

    public String getRemainingText() {
        String result = "";

        if(useDistanceForAlarm) {
            result += distanceRun() + " " + units;
            result += " / ";
            result += distanceForAlarm + " " + units;
        } else {
            result = endTime.toString();
        }

        return result;
    }

    boolean showStop() { return running; }

    public String getAverageSpeed() {
        Time time;
        if(lapLength == 0 || lapCount == 0)
            time = new Time(0);
        else
            time = new Time(Math.round((double)timeLastLap / (lapLength * lapCount)));
        return time.toString() + " " + context.getString(R.string.per) + " " + units;
    }

    public void triggerAlarm() {
        if(!alarmTriggered) {
            alarmTriggered = true;
            //Start the notification
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setMessage(R.string.alarm_dialog_message)
                    .setPositiveButton(R.string.dialog_button_text, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            vibrator.cancel();
                        }
                    }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    vibrator.cancel();
                }
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        vibrator.cancel();
                    }
                });
            }
            alarmDialog = builder.create();
            alarmDialog.show();

            //Vibrate
            long[] times = {0, 500, 500};
            vibrator.vibrate(times, 1);
        }
    }

    public int getLapCount() { return lapCount; }

    public boolean isDone() { return done; }
}
