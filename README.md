# Google Pixel Dual Camera Streamer


This Android application allows you to simultaneously stream video from two rear cameras of a Google Pixel device (or other compatible Android devices supporting logical multi camera API) to computer via MJPEG over TCP.

Combined with a python script, it allows both cameras to be used as virtual webcams for stereo or whatever else.

## Features

- **Dual Camera Streaming**: Simultaneously captures and streams from both the Wide and Ultra Wide lenses.
- **MJPEG Server**: Runs two lightweight MJPEG servers on the device.
- **Virtual Webcam Support**: Includes a Python script to forward these streams to virtual camera devices on windows/linux.

## Prerequisites

- **Android Device**: A device with multi-camera support (tested on Google Pixel).
- **Android Studio**: To build and install the application.
- **ADB (Android Debug Bridge)**: For port forwarding.
- **Python 3.x**: For the virtual camera script.

## Setup & Installation

1.  **Clone the Repository**
    ```bash
    git clone <repository-url>
    cd GooglePixel-Dual-Camera
    ```

2.  **Build and Install the App**
    - Open the project in Android Studio.
    - Connect your Android device via USB (ensure USB Debugging is enabled).
    - Build and run the application on your device.
    - Grant the necessary camera permissions when prompted.

3.  **Port Forwarding**
    The app streams the Wide camera on port `8000` and the Ultra Wide camera on port `8001`. You need to forward these ports from your device to your computer using ADB.

    ```bash
    adb forward tcp:8000 tcp:8000
    adb forward tcp:8001 tcp:8001
    ```

## Usage

1.  **Start the App**: Open the app on your phone. You should see the two camera previews.
2.  **Start Virtual Cameras**: Use the provided Python script to create virtual webcams from the streams.

    For detailed instructions on setting up the virtual cameras, please refer to [VirtualCameras.MD](VirtualCameras.MD).

    **Quick Start (Linux):**
    ```bash
    # For Wide Camera (Port 8000) -> /dev/video10
    python create_virtual_camera.py --port 8000 --device /dev/video10

    # For Ultra Wide Camera (Port 8001) -> /dev/video11
    python create_virtual_camera.py --port 8001 --device /dev/video11
    ```

## Project Structure

- `app/`: Android application source code.
- `create_virtual_camera.py`: Python script for creating virtual webcams.
- `VirtualCameras.MD`: Detailed documentation for the virtual camera setup.
