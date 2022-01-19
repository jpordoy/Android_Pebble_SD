package uk.org.openseizuredetector;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class EditEventActivity extends AppCompatActivity
        implements AuthCallbackInterface, EventCallbackInterface, DatapointCallbackInterface {
    private String TAG = "EditEventActivity";
    private Context mContext;
    private WebApiConnection mWac;
    private LogManager mLm;
    final Handler serverStatusHandler = new Handler();
    private OsdUtil mUtil;
    private List<String> mEventTypesList = null;
    private HashMap<String, ArrayList<String>> mEventSubTypesHashMap = null;
    private String mEventTypeStr = null;
    private String mEventSubTypeStr = null;
    private Long mEventId;
    private String mEventNotes = "";
    //private Date mEventDateTime;
    private RadioGroup mEventTypeRg;
    private boolean mEventTypesListChanged = false;
    private RadioGroup mEventSubTypeRg;
    private boolean mEventSubTypesListChanged = false;
    private JSONObject mEventObj;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_event);

        mWac = new WebApiConnection(this, this, this, this);
        mLm = new LogManager(this);


        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            Long eventId = extras.getLong("eventId");
            mEventId = eventId;
            Log.v(TAG, "onCreate - mEventId=" + mEventId);
            try {
                mWac.getEvent(mEventId, (JSONObject eventObj) -> {
                    Log.v(TAG,"onCreate.getEvent");
                            mEventObj = eventObj;
                            Log.v(TAG, "onCreate.getEvent:  eventObj=" + eventObj.toString());
                            updateUi();
                            // FIXME: modify updateUi to use mEventObj
                        }
                );
            } catch (Exception e) {
                Log.e(TAG,"ERROR:"+e.getMessage());
                e.printStackTrace();
            }
        }
        mUtil = new OsdUtil(this, serverStatusHandler);

        Button cancelBtn =
                (Button) findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);
        Button OKBtn = (Button) findViewById(R.id.OKBtn);
        OKBtn.setOnClickListener(onOK);

        mEventTypeRg = findViewById(R.id.eventTypeRg);
        mEventTypeRg.setOnCheckedChangeListener(onEventTypeChange);
        mEventSubTypeRg = findViewById(R.id.eventSubTypeRg);
        mEventSubTypeRg.setOnCheckedChangeListener(onEventSubTypeChange);


        // Retrieve the JSONObject containing the standard event types.
        // Note this obscure syntax is to avoid having to create another interface, so it is worth it :)
        // See https://medium.com/@pra4mesh/callback-function-in-java-20fa48b27797
        mWac.getEventTypes((JSONObject eventTypesObj) -> {
            Log.v(TAG, "onCreate.onEventTypesReceived");
            if (eventTypesObj == null) {
                Log.e(TAG, "onCreate.getEventTypes Callback:  Error Retrieving event types");
                mUtil.showToast("Error Retrieving Event Types from Server - Please Try Again Later!");
            } else {
                Iterator<String> keys = eventTypesObj.keys();
                mEventTypesList = new ArrayList<String>();
                mEventSubTypesHashMap = new HashMap<String, ArrayList<String>>();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Log.v(TAG, "onCreate.getEventTypes Callback: key=" + key);
                    mEventTypesList.add(key);
                    try {
                        JSONArray eventSubTypes = eventTypesObj.getJSONArray(key);
                        ArrayList<String> eventSubtypesList = new ArrayList<String>();
                        for (int i = 0; i < eventSubTypes.length(); i++) {
                            eventSubtypesList.add(eventSubTypes.getString(i));
                        }
                        mEventSubTypesHashMap.put(key, eventSubtypesList);
                        mEventTypesListChanged = true;
                    } catch (JSONException e) {
                        Log.e(TAG, "onCreate(getEventTypes Callback: Error parsing JSONObject" + e.getMessage() + e.toString());
                    }
                }
                updateUi();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");
        updateUi();
    }

    public void authCallback(boolean authSuccess, String tokenStr) {
        Log.v(TAG, "authCallback");
        updateUi();
    }

    public void eventCallback(boolean success, String eventStr) {
        Log.v(TAG, "eventCallback");
    }

    public void datapointCallback(boolean success, String datapointStr) {
        Log.v(TAG, "datapointCallback");
    }

    private void updateUi() {
        Log.v(TAG, "updateUI");
        TextView tv;
        RadioButton b;

        // Populate event type button group if necessary
        if (mEventTypesList != null && mEventTypesListChanged) {
            Log.v(TAG, "updateUi: " + mEventTypesList.toString());
            mEventTypeRg.removeAllViews();
            for (String eventTypeStr : mEventTypesList) {
                b = new RadioButton(this);
                b.setText(eventTypeStr);
                mEventTypeRg.addView(b);
            }
            mEventTypesListChanged = false;
        }


        try {
            if (mEventObj != null) {
                tv = (TextView) findViewById(R.id.eventIdTv);
                tv.setText(String.valueOf(mEventObj.getLong("id")));
                tv = (TextView) findViewById(R.id.eventNotsTv);
                tv.setText(mEventObj.getString("desc"));


                tv = (TextView) findViewById(R.id.eventDateTv);
                try {
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                    Date dataTime = dateFormat.parse(mEventObj.getString("dataTime"));
                    dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    tv.setText(dateFormat.format(dataTime));
                } catch (ParseException e) {
                    Log.e(TAG,"updateUI: Error Parsing dataDate "+e.getLocalizedMessage());
                    tv.setText("---");
                }

                // Check the correct seizure type button in the event type group
                for (int index = 0; index < mEventTypeRg.getChildCount(); index++) {
                    b = (RadioButton) mEventTypeRg.getChildAt(index);
                    String buttonText = b.getText().toString();
                    if (buttonText.equals(mEventObj.getString("type"))) {
                        Log.v(TAG, "updateUi - selecting button " + mEventObj.getString("type"));
                        b.setChecked(true);
                    }
                }

                // Populate the event sub-types radio button list.
                Log.v(TAG,"updateUi() - meventsubtypeshashmap="+mEventSubTypesHashMap+", mEventSubtypesListChanged="+mEventSubTypesListChanged);
                if (mEventSubTypesHashMap != null && mEventSubTypesListChanged) {
                    Log.v(TAG,"UpdateUi() - populating event sub types list");
                    if (mEventObj.getString("type") != null) {
                        // based on https://androidexample.com/create-a-simple-listview
                        ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(mEventObj.getString("type"));
                        Log.v(TAG, "updateUi() - eventType=" + mEventObj.getString("type") + ", subtypes=" + subtypesArrayList);
                        mEventSubTypeRg.removeAllViews();
                        for (String eventSubTypeStr : subtypesArrayList) {
                            b = new RadioButton(this);
                            b.setText(eventSubTypeStr);
                            mEventSubTypeRg.addView(b);
                        }
                        mEventSubTypesListChanged = false;
                    }
                }


                // And show the correct sub-type selected.
                for (int index = 0; index < mEventSubTypeRg.getChildCount(); index++) {
                    b = (RadioButton) mEventSubTypeRg.getChildAt(index);
                    String buttonText = b.getText().toString();
                    if (buttonText.equals(mEventObj.getString("subType"))) {
                        Log.v(TAG, "updateUi - selecting button " + mEventObj.getString("subType"));
                        b.setChecked(true);
                    }
                }


            }
        } catch (JSONException e) {
            Log.e(TAG,"Error Parsing mEventObj: "+e.getMessage());
        }



    }  // updateUi()

    View.OnClickListener onCancel =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCancel");
                    //m_status=false;
                    finish();
                }
            };

    View.OnClickListener onOK =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    TextView tv = (TextView)findViewById(R.id.eventNotsTv);
                    try {
                        mEventObj.put("desc",tv.getText());
                    } catch (JSONException e) {
                        Log.e(TAG,"Error writing mEventObj: "+e.getMessage());
                    }
                    Log.v(TAG, "onOK() - eventObj="+mEventObj.toString());


                    try {
                        mWac.updateEvent(mEventObj, (JSONObject eventObj) -> {
                                    Log.v(TAG,"onOk.updateEvent");
                                    //mEventObj = eventObj;
                                    if (eventObj != null) {
                                        Log.v(TAG, "onOk.getEvent:  eventObj=" + eventObj.toString());
                                        mUtil.showToast("Event Updated OK");
                                        finish();
                                    } else {
                                        Log.e(TAG,"onOk.updateEvent - Error - returned NULL");
                                        mUtil.showToast("Error Updating Event");
                                        updateUi();
                                    }
                                }
                        );
                    } catch (Exception e) {
                        Log.e(TAG,"ERROR:"+e.getMessage());
                        e.printStackTrace();
                        mUtil.showToast("Error Updating Event");
                        updateUi();
                    }

                    //String uname = mUnameEt.getText().toString();
                    //String passwd = mPasswdEt.getText().toString();
                    //Log.v(TAG,"onOK() - uname="+uname+", passwd="+passwd);
                    //mWac.authenticate(uname,passwd);
                    //finish();
                }
            };


    RadioGroup.OnCheckedChangeListener onEventTypeChange =
            new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    Log.v(TAG,"onEventTypeChange() - id="+checkedId);
                    RadioButton b = (RadioButton)findViewById(group.getCheckedRadioButtonId());
                    String selectedEventType = b.getText().toString();
                    try {
                        mEventObj.put("type", selectedEventType);
                    } catch (JSONException e) {
                        Log.e(TAG,"Error setting mEventObj.type: "+e.getMessage());
                    }
                    mEventSubTypesListChanged = true;
                    Log.v(TAG,"onEventTypeChange() - mEventSubTypesListChanged="+mEventSubTypesListChanged);
                    updateUi();
                }
            };
    RadioGroup.OnCheckedChangeListener onEventSubTypeChange =
            new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    Log.v(TAG,"onEventSubTypeChange() - id="+checkedId);
                    RadioButton b = (RadioButton)findViewById(group.getCheckedRadioButtonId());
                    String selectedEventSubType = b.getText().toString();
                    try {
                        mEventObj.put("subType", selectedEventSubType);
                    } catch (JSONException e) {
                        Log.e(TAG,"Error setting mEventObj.type: "+e.getMessage());
                    }
                    updateUi();
                }
            };


}