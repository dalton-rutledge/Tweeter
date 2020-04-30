package drutledge.tweeter;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.innovattic.rangeseekbar.RangeSeekBar;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    //Declarations:
    final int REQUEST_PERMISSION_CODE = 1000;
    Interpreter tflite;
    String modelFile = "tweeterpredictor.tflite";

    //Default Librosa Audio Sample Settings:
    private static final int SAMPLE_RATE = 22050;
    //Recording a sample for 15 seconds
    private static final int SAMPLE_DURATION_MS = 15000;
    //length of the recording
    private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
    //Variable that stores the current recording as an array of shorts
    short[] recordingBuffer = new short[RECORDING_LENGTH];
    //Variable that stores the current clipping as an array of shorts (not initialized)
    short[] clippingBuffer;

    //Handler for posting UI updates from background threads
    private Handler mainHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Request necessary permissions immediatly
        if(!checkPermissionFromDevice()){
            requestPermission();
        }

        // INITIALIZE VIEW
        final Button sample_button = findViewById(R.id.sample_button);
        final Button submit_button = findViewById(R.id.submit_button);
        final Button clip_button = findViewById(R.id.clip_button);
        final RangeSeekBar range = findViewById(R.id.rangeSeekBar);
        final ImageView results_image = findViewById(R.id.results_image);
        final TextView results_text = findViewById(R.id.results_text);
        //initialize the range
        range.setMinRange(1);
        range.setMax(15);

        //Disable range seeker and audio clipping/submission until the recording is actually in
        clip_button.setVisibility(View.GONE);
        range.setVisibility(View.GONE);
        submit_button.setVisibility(View.GONE);



        //Attempt to load the ANN model from assets
        try{
            tflite = new Interpreter(loadModelFile(this, modelFile));
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        //Only run the app and attempt to record Audio if the app has permission to do so!
        if(checkPermissionFromDevice()) {

            //sampling functionality, clicking this button will start a 15 seconds recording using the phones microphone.
            sample_button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    //Start by updating the UI accordingly: sample button goes away, text and image are reset
                    results_image.setImageResource(R.drawable.recordingimage);
                    results_text.setText("");
                    sample_button.setVisibility(View.GONE);

                    //Run all functional code on a new thread to keep the UI thread running at 60 fps
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // Estimate the buffer size we'll need for this device.
                            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                                bufferSize = SAMPLE_RATE * 2;
                            }

                            short[] audioBuffer = new short[bufferSize / 2];

                            //setup Audio Recorder to load in 15 seconds of audio. Set to take audio in the same way that librosa does
                            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                            record.startRecording();
                            boolean recording = true;
                            int offset = 0;
                            while (recording) {
                                int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
                                if (offset + numberRead < recordingBuffer.length) {
                                    System.arraycopy(audioBuffer, 0, recordingBuffer, offset, numberRead);
                                } else {
                                    recording = false;
                                }
                                offset += numberRead;
                            }


                            //release unneeded resources
                            record.stop();
                            record.release();

                            //Finish by updating the UI, so that sample button is gone, but submit button and range seeker are now visible
                            //doing it this way prevents the user from progressing through the stages of the app
                            //before this background thread has completed execution
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    results_image.setImageResource(R.drawable.trimmingimage);
                                    clip_button.setVisibility(View.VISIBLE);
                                    submit_button.setVisibility(View.VISIBLE);
                                    submit_button.setEnabled(false);
                                    range.setVisibility(View.VISIBLE);
                                }
                            });

                        }
                    }).start();
                }
            });

            //This button updates the audio recorded and plays it back to the user.
            clip_button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    //Update the UI accordingly. To prevent data races, we only want this button to be able to be clicked once.
                    clip_button.setEnabled(false);
                    submit_button.setEnabled(false);

                    //run all heavy lifting code in a background thread to keep UI running smoothly
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            //Get range seeker input and initialize clipping buffer accordingly
                            int rangeMin = range.getMinThumbValue();
                            int rangeMax = range.getMaxThumbValue();

                            int clipLength = (int) (SAMPLE_RATE * (rangeMax - rangeMin));
                            clippingBuffer = new short[clipLength];

                            //copy the correct part of the recording buffer into the clipping buffer:
                            System.arraycopy(recordingBuffer , rangeMin * SAMPLE_RATE, clippingBuffer, 0, clippingBuffer.length);

                            //Make a copy of the clipping buffer to be fed to the audio track
                            short[] tempBuffer = new short[clipLength];
                            System.arraycopy(clippingBuffer, 0 , tempBuffer, 0 , clippingBuffer.length);

                            //play back the clipped sound using Audio Track
                            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                            AudioTrack audio = new AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    SAMPLE_RATE,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    minBufferSize,
                                    AudioTrack.MODE_STREAM );
                            audio.play();
                            audio.write(tempBuffer, 0, tempBuffer.length);
                            audio.release();


                            //update the UI. We only want to be able to submit after clipping
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    submit_button.setEnabled(true);
                                    clip_button.setEnabled(true);
                                }
                            });
                        }
                    }).start();
                }
            });


            //submitting for classification, this button will process a clipped version (determined by the range bar) of the recording, then submit to
            // the ANN and update the UI accordingly
            submit_button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    //start by updating the UI, after submitting, we want the clip/submit and range seeker gone.
                    submit_button.setVisibility(View.GONE);
                    clip_button.setVisibility(View.GONE);
                    range.setVisibility(View.GONE);

                    //Run heavy lifting code through a background thread

                    new Thread(new Runnable() {
                        @Override
                        public void run() {

                            int clipLength = (SAMPLE_RATE * (range.getMaxThumbValue() - range.getMinThumbValue()));

                            //copy the clipping buffer into a temp variable
                            short[] inputBuffer = new short[clipLength];
                            System.arraycopy(clippingBuffer, 0, inputBuffer, 0, clipLength);


                            double[] doubleInputBuffer = new double[clipLength];
                            // We need to feed in float values between -1.0 and 1.0, so divide the signed 16-bit inputs. This is to mimic librosa
                            for (int i = 0; i < inputBuffer.length; ++i) {
                                doubleInputBuffer[i] = inputBuffer[i] / 32767.0;
                            }

                            //calculate mfccs. Librosa returns arrays mfccs which we then average into 20 distinct features
                            MFCC mfccConvert = new MFCC();
                            float[] mfcc = mfccConvert.process(doubleInputBuffer);

                            //we need to average the MFCCS down to 20.
                            float[] mfccProcessed = new float[20];
                            int mfccsToAverage = mfcc.length / 20;
                            int offset = 0;
                            for (int i = 0; i < 20; ++i){
                                float sum = 0.0F;
                                for (int j = 0; j < mfccsToAverage; ++j){
                                    sum += mfcc[offset];
                                    offset++;
                                }
                                mfccProcessed[i] = sum/20;
                            }

                            //Log processed mfccs for testing and debugging
                            Log.v("MFCC IN:", "===========> " + Arrays.toString(mfccProcessed));

                            //format processed mfccs correctly
                            float[][] inferenceReadyMFCC = new float[][]{mfccProcessed};

                            //Run mfccs through the classifier and save into a results array. Log results for testing
                            float[][] results = doInference(inferenceReadyMFCC);
                            Log.v("NETWORK RESULTS:", "============> " + Arrays.toString(results[0]));

                            //display image and text based on classification result
                            int bestInference = indexOfLargest(results[0]);
                            if(bestInference == -1){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.tl);
                                        results_text.setText("Cannot classify");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 0){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.ac);
                                        results_text.setText("American Crow");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 1){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.bcc);
                                        results_text.setText("Black-capped Chickadee");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 2){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.cw);
                                        results_text.setText("Cactus Wren");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 3){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.hf);
                                        results_text.setText("House Finch");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 4){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.md);
                                        results_text.setText("Mourning Dove");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 5){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.nc);
                                        results_text.setText("Northern Cardinal");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 6){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.wt);
                                        results_text.setText("Wood Thrush (Call)");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 7){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.wt);
                                        results_text.setText("Wood Thrush (Song)");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 8){
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.ttm);
                                        results_text.setText("Tufted Titmouse");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                            else if(bestInference == 9) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        results_image.setImageResource(R.drawable.wbnh);
                                        results_text.setText("White-breasted Nuthatch");
                                        sample_button.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                        }
                    }).start();

                }
            });
        }

        //Request permission to use audio if we do not have it
        else {
            requestPermission();
        }
    }

    /*******************Helper methods*******************/

    //default function to request permissions
    private void requestPermission(){
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO
        }, REQUEST_PERMISSION_CODE);
    }

    //Display small pop-up confirmation of a user's choice to grant or deny permissions
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode)
        {
            case REQUEST_PERMISSION_CODE:
            {
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show();
                }
                else{
                    Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
                }
            }
                break;
        }
    }

    //check permission to record audio and write audio files
    private boolean checkPermissionFromDevice(){
        int write_external_storage_result = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int record_audio_result = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return write_external_storage_result == PackageManager.PERMISSION_GRANTED && record_audio_result == PackageManager.PERMISSION_GRANTED;
    }

    //helper function for finding the index of the largest returned float
    public int indexOfLargest(float[] array){
        int i;
        int index = 0;
        float max = array[0];
        for (i=1; i< array.length; i++){
            if (array[i] > max){
                max = array[i];
                index = i;
            }
        }
        if(max < .7){
            index = -1;
        }
        return index;
    }

    //Runs a set of float parameters (MFCCs) through the ANN, returning a float[1][10]
    public float[][] doInference(float[][] calculatedMFCCs){
        float[][] outputval = new float[1][10];
        tflite.run(calculatedMFCCs, outputval);
        return outputval;
    }


    //Default Function for loading in tflite files
    private MappedByteBuffer loadModelFile(Activity activity, String MODEL_FILE) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

}
