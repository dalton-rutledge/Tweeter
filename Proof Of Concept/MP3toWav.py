'''
Dalton Rutledge
Tweeter Model Development 2019-2020 Capstone
This file converts all .mp3 files in a directory to .wav files.
I can download .mp3 files directly from the Xeno Canto database at https://www.xeno-canto.org/

The Android SDK for media reading (from the microphone) includes the ability to save recorded audio as .wav
Look at spectroGraph.py to see turning .wav files into images. Images are easiest for a CNN to operate on, as well as for many other kinds of models.
This is why I want to be able to format my files as images. 

See https://stackoverflow.com/questions/17192256/recording-wav-with-android-audiorecorder for resources about recording into .wav files
from Android microphone

See https://play.google.com/store/apps/details?id=net.galmiza.android.spectrogram&hl=en_US 
for proof of being able to implement FFT on android for spectogram generation. 

'''

import os
from pydub import AudioSegment

#directory containaing all mp3 files you want to convert to .wav
directory = "C:/Users/dalto/OneDrive/Desktop/CS 2020 Final Project/Proof Of Concept"

#loop through .mp3 files and create their .wav counterparts using AudioSegment library
for filename in os.listdir(directory):
    if filename.endswith(".mp3"):
        output = filename[0:-4] + ".wav"

        # convert wav to mp3                                                            
        sound = AudioSegment.from_mp3(filename)
        sound.export(output, format="wav")