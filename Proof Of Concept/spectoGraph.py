'''
Dalton Rutledge
Tweeter Model Development 2019-2020 Capstone

This file takes all mp3 files in a directory and converts them into an image spectogram.
That image is then saved in the same directory as "originalFileName.png"
'''

import os
import matplotlib.pyplot as pyplot
from scipy.io import wavfile
import numpy as np

#directory containaing all .wav files you want to convert to .png
directory = "C:/Users/dalto/OneDrive/Desktop/CS 2020 Final Project/Proof Of Concept"

#loop through .wav files and create their .png counterparts using pyplot
for filename in os.listdir(directory):
    if filename.endswith(".wav"):

        #file to save to
        output = filename[0:-4] + ".png"
        # Read the wav file (mono) --> Note that it would be good to seperate left and right audio streams for final implementation
        samplingFrequency, signalData = wavfile.read(filename)
        #reshape data to work for the spectogram using numpy
        signalData = np.array(signalData)
        signal = np.reshape(signalData, signalData.size)
        signal = signal.tolist()
        # Plot the signal read from wav file
        pyplot.specgram(signal,NFFT=10000,Fs=samplingFrequency)
        pyplot.axis('off')
        pyplot.savefig(output, bbox_inches="tight", pad_inches=0)
