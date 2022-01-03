/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2019, 2021.

  This file is part of Android_SD.

  Android_SD is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_SD is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_SD.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.format.Time;
import android.util.Log;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static android.database.sqlite.SQLiteDatabase.openOrCreateDatabase;

/**
 * LogManager is a class to handle all aspects of Data Logging within OpenSeizureDetector.
 * It performs several functions:
 *  - It will store seizure detector data to a local database on demand (it is called by the SdServer background service)
 *  - <FIXME: Not Yet Implemented> It will retrieve data from the local database for display on the user interface
 *  - <FIXME: Not Yet Implemented> It will periodically trim the local database to retain only a specified number of days worth of data
 *        to avoid the local storage use increasing continuously.
 *  - It will periodically attempt to upload the oldest logged data to the osdApi remote database - the interface to the
 *       remote database is handled by the WebApiConnection class.   It only tries to do one transaction with the external database
 *       at a time - if the periodic timer times out and an upload is in progress it will not do anything and wait for the next timeout.*
 *
 *  The data upload process is as follows:
 *  - Select the oldest non-uploaded datapoint that is marked as an alarm or warning state.
 *  - Create an Event in the remote database based on that datapoint date and alarm type, and note the Event ID.
 *  - Query the local database to return all datapoints within +/- EventDuration/2 minutes of the event.
 *  - Upload the datapoints, linking them to the new eventID.
 *  - Mark all the uploaded datapoints as uploaded.
 */
public class LogManager {
    private String TAG = "LogManager";
    private String mDbName = "osdData";
    private String mDbTableName = "datapoints";
    private boolean mLogRemote;
    private boolean mLogRemoteMobile;
    //private String mOSDUrl = "https://https://osd.dynu.net/";
    //private String mApiToken;
    private OsdDbHelper mOSDDb;
    private RemoteLogTimer mRemoteLogTimer;
    private Context mContext;
    private OsdUtil mUtil;

    private boolean mUploadInProgress;
    private int mUploadingDatapointId;


    public LogManager(Context context) {
        Log.d(TAG,"LogManger Constructor");
        mLogRemote = false;
        mLogRemoteMobile = false;
        //mOSDUrl = null;
        mContext = context;

        Handler handler = new Handler();
        mUtil = new OsdUtil(mContext, handler);
        openDb();
        startRemoteLogTimer();
    }

    /**
     * Returns a JSON String representing an array of datapoints that are selected from sqlite cursor c.
     * @param c sqlite cursor pointing to datapoints query result.
     * @return JSON String.
     * from https://stackoverflow.com/a/20488153/2104584
     */
    private String cursor2Json(Cursor c) {
        String retVal = "";
        c.moveToFirst();
        //JSONObject Root = new JSONObject();
        JSONArray dataPointArray = new JSONArray();
        int i = 0;
        while (!c.isAfterLast()) {
            JSONObject datapoint = new JSONObject();
            try {
                datapoint.put("id", c.getString(c.getColumnIndex("id")));
                datapoint.put("dataTime", c.getString(c.getColumnIndex("dataTime")));
                datapoint.put("status", c.getString(c.getColumnIndex("Status")));
                datapoint.put("dataJSON", c.getString(c.getColumnIndex("dataJSON")));
                datapoint.put("uploaded", c.getString(c.getColumnIndex("uploaded")));
                c.moveToNext();
                dataPointArray.put(i, datapoint);
                i++;
            } catch (JSONException e) {

                e.printStackTrace();
            }
        }
        return dataPointArray.toString();
    }




    private boolean openDb() {
        Log.d(TAG, "openDb");
        try {
            mOSDDb = new OsdDbHelper(mDbTableName, mContext);
            if (!checkTableExists(mOSDDb, mDbTableName)) {
                Log.e(TAG,"ERROR - Table does not exist");
                return false;
            }
            else {
                Log.d(TAG,"table "+mDbTableName+" exists ok");
            }
            return true;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString());
            mOSDDb = null;
            return false;
        }
    }

    private boolean checkTableExists(OsdDbHelper osdDb, String osdTableName) {
        Cursor c = null;
        boolean tableExists = false;
        Log.d(TAG,"checkTableExists()");
        try {
            c = osdDb.getWritableDatabase().query(osdTableName, null,
                    null, null, null, null, null);
            tableExists = true;
        }
        catch (Exception e) {
            Log.d(TAG, osdTableName+" doesn't exist :(((");
        }
        return tableExists;
    }


    /**
     * Write data to local database
     * FIXME - I am sure we should not be using raw SQL Srings to do this!
     */
    public void writeToLocalDb(SdData sdData) {
        Log.v(TAG, "writeToLocalDb()");
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");
        String SQLStr = "SQLStr";

        try {
            double  roiRatio = -1;
            if (sdData.specPower != 0)
                roiRatio = 10. * sdData.roiPower / sdData.specPower;
            SQLStr = "INSERT INTO "+ mDbTableName
                    + "(dataTime, status, dataJSON, uploaded)"
                    + " VALUES("
                    +"CURRENT_TIMESTAMP,"
                    + sdData.alarmState + ","
                    + DatabaseUtils.sqlEscapeString(sdData.toJSON(true)) + ","
                    + 0
                    +")";
            mOSDDb.getWritableDatabase().execSQL(SQLStr);
            Log.d(TAG,"data written to database");

        } catch (SQLException e) {
            Log.e(TAG,"writeToLocalDb(): Error Writing Data: " + e.toString());
            Log.e(TAG,"SQLStr was "+SQLStr);
        }

    }

    /**
     * Returns a json representation of datapoint 'id'.
     * @param id datapoint id to return
     * @return JSON representation of requested datapoint (single element JSON array)
     */
    public String getDatapointById(int id) {
        Log.d(TAG,"getDatapointById() - id="+id);
        Cursor c = null;
        String retVal;
        try {
            String selectStr = "id =?";
            String[] selectArgs = new String[]{String.format("%d",id)};
            c = mOSDDb.getWritableDatabase().query(mDbTableName, null,
                    selectStr, selectArgs, null, null, null);
            retVal = cursor2Json(c);
        }
        catch (Exception e) {
            Log.d(TAG,"getDatapointById(): Error Querying Database: "+e.getLocalizedMessage());
            retVal = null;
        }
        return(retVal);

    }

    /**
     * getDatapointsJSON() Returns a JSON Object of all of the datapoints in the local database
     * between endDateStr-duration and endDateStr
     * @param endDateStr  String representation of the period end date
     * @param duration Duration in minutes.
     * @return JSONObject of all the datapoints in the range.
     */
    public String getDatapointsbyDate(String startDateStr, String endDateStr) {
        Log.d(TAG,"queryDatapoints() - endDateStr="+endDateStr);
        Cursor c = null;
        String retVal;
        try {
            String selectStr = "DataTime>=? and DataTime<=?";
            String[] selectArgs = {startDateStr, endDateStr};
            c = mOSDDb.getWritableDatabase().query(mDbTableName, null,
                    null, null, null, null, null);
            //c.query("Select * from ? where DataTime < ?", mDbTableName, endDateStr);
            retVal = cursor2Json(c);
        }
        catch (Exception e) {
            Log.d(TAG, mDbTableName+" doesn't exist :(((");
            retVal = null;
        }
        return(retVal);
    }




    /**
     * Return the ID of the next datapoint that needs to be uploaded (alarm or warning condition and has not yet been uploaded.
     */
    public int eventToUpload(boolean includeWarnings) {
        Log.v(TAG, "getLocalEvents()");
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");
        String SQLStr = "SQLStr";
        String statusListStr;
        String recordStr;
        int recordId;

        if (includeWarnings) {
            statusListStr ="1,2,3,5";   // Warning, Alarm, Fall, Manual Alarm
        } else {
            statusListStr = "2,3,5";    // Alarm, Fall, Manual Alarm
        }
        try {
            SQLStr = "SELECT * from "+ mDbTableName + " where uploaded=false and Status in ("+statusListStr+");";
            Cursor resultSet = mOSDDb.getWritableDatabase().rawQuery(SQLStr,null);
            resultSet.moveToFirst();
            recordStr = resultSet.getString(3);
            recordId = resultSet.getInt(0);
            Log.d(TAG,"getLocalEvents: "+recordStr);

        } catch (SQLException e) {
            Log.e(TAG,"writeToLocalDb(): Error selecting Data: " + e.toString());
            Log.e(TAG,"SQLStr was "+SQLStr);
            recordStr = "ERROR";
            recordId = -1;
        }
    return (recordId);
    }


    public void writeToRemoteServer() {
        Log.v(TAG,"writeToRemoteServer()");
        if (!mLogRemote) {
            Log.v(TAG,"writeToRemoteServer(): mLogRemote not set, not doing anything");
            return;
        }

        if (!mLogRemoteMobile) {
            // Check network state - are we using mobile data?
            if (mUtil.isMobileDataActive()) {
                Log.v(TAG,"writeToRemoteServer(): Using mobile data, so not doing anything");
                return;
            }
        }

        if (!mUtil.isNetworkConnected()) {
            Log.v(TAG,"writeToRemoteServer(): No network connection - doing nothing");
            return;
        }

        if (mUploadInProgress) {
            Log.v(TAG,"writeToRemoteServer(): Upload already in progress, not starting another upload");
            return;
        }

        Log.v(TAG,"writeToRemoteServer(): calling UploadSdData()");
        uploadSdData();
    }



    /**
     * Upload a batch of seizure detector data records to the server..
     * Uses the webApiConnection class to upload the data in the background.
     * It searches the local database for the oldest event that has not been uploaded and uploads it.
     * eventCallback is called when the event is created.
     */
    public void uploadSdData() {
        Log.v(TAG, "uploadSdData()");
        String dataStr = "data string to upload";
        //new PostDataTask().execute("http://" + mOSDUrl + ":8080/data", dataStr, mOSDUname, mOSDPasswd);
        //new PostDataTask().execute("http://192.168.43.175:8765/datapoints/add", dataStr, mOSDUname, mOSDPasswd);
    }


    // Called by WebApiConnection when a new event record is created.
    // Once the event is created it queries the local database to find the datapoints associated with the event
    // and uploads those as a batch of data points.
    public void eventCallback(boolean success, String eventStr) {
        Log.v(TAG,"eventCallback(): " + eventStr);
    }

    // Called by WebApiConnection when a new datapoint is created
    public void datapointCallback(boolean success, String datapointStr) {
        Log.v(TAG,"datapointCallback() " + datapointStr);
    }



    public void close() {
        mOSDDb.close();
        stopRemoteLogTimer();
    }

    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
        Log.v(TAG, "startRemoteLogTimer() - starting RemoteLogTimer");
        mRemoteLogTimer =
                new RemoteLogTimer(10 * 1000, 1000);
        mRemoteLogTimer.start();
    }


    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "stopRemoteLogTimer(): cancelling Remote Log timer");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
    }



    public class OsdDbHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "OsdData.db";
        private String mOsdTableName;
        private String TAG = "LogManager.OsdDbHelper";

        public OsdDbHelper(String osdTableName, Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdDbHelper constructor");
            mOsdTableName = osdTableName;
        }

        public void onCreate(SQLiteDatabase db) {
            Log.v(TAG, "onCreate - TableName=" + mOsdTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + mOsdTableName + "("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "Status INT,"
                    + "dataJSON TEXT,"
                    + "uploaded INT"
                    + ");";

            db.execSQL(SQLStr);
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to simply to discard the data and start over
            db.execSQL("Drop table if exists " + mOsdTableName + ";");
            onCreate(db);
        }

        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onUpgrade(db, oldVersion, newVersion);
        }
    }

    /**
     * Upload recorded data to the remote database periodically.
     */
    private class RemoteLogTimer extends CountDownTimer {
        public RemoteLogTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onTick(long l) {
            // Do Nothing
        }

        @Override
        public void onFinish() {
            Log.v(TAG, "mRemoteLogTimer - onFinish - uploading data to remote database");
            writeToRemoteServer();
            // Restart this timer.
            start();
        }

    }

}
