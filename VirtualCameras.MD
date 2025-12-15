# Virtual Webcam Forwarder

This Python script forwards a video stream (e.g., from an Android device via ADB) to a virtual webcam device on your computer. This allows you to use the stream in applications like Zoom, Google Meet, OBS, or any other software that expects a webcam.

## Prerequisites

- Python 3.x
- ADB (Android Debug Bridge) installed and in your PATH.

## Installation

1.  **Install Python Dependencies:**

    ```bash
    pip install opencv-python pyvirtualcam numpy
    ```

2.  **Platform Specific Setup:**

    ### Linux
    You need the `v4l2loopback` kernel module.

    *   **Install:**
        ```bash
        sudo apt install v4l2loopback-dkms v4l2loopback-utils
        ```
    *   **Configure (IMPORTANT):**
        To ensure compatibility with Chrome, Zoom, and other apps, you must load the module with specific parameters (`exclusive_caps=1` and `card_label`).

        Run this command to create a virtual camera device (e.g., `/dev/video5`):
        ```bash
        sudo modprobe -r v4l2loopback
        sudo modprobe v4l2loopback devices=2 exclusive_caps=1 video_nr=10,11 card_label="VirtualCam1, VirtualCam2" max_buffers=2 max_openers=10
        ```

    ### Windows
    `pyvirtualcam` on Windows typically relies on the **OBS Virtual Camera**.

    1.  Install [OBS Studio](https://obsproject.com/).
    2.  Open OBS Studio.
    3.  Click "Start Virtual Camera" in the controls dock (you don't need to keep OBS open, just registering the device usually works, but keeping it open ensures the driver is active).
    4.  Alternatively, you can use [Unity Capture](https://github.com/schellingb/UnityCapture) if you prefer not to use OBS.

## Usage

1.  **Setup ADB Forwarding:**
    Connect your Android device and forward the port used by your IP webcam app (e.g., 8000).
    ```bash
    adb forward tcp:8000 tcp:8000
    ```

2.  **Run the Script:**
    ```bash
    python virtualWebcam.py
    ```

3.  **Select the Camera:**
    Open your video conferencing app (Zoom, Meet, etc.) and select **"VirtualCam"** (Linux) or **"OBS Virtual Camera"** (Windows) as your video input.

## Advanced Usage

### Command Line Arguments
- `--host`: Host address of the stream (default: `localhost`)
- `--port`: Port of the stream (default: `8000`)
- `--device`: (Linux only) Manually specify the virtual device path (e.g., `/dev/video10`).

### Multiple Cameras (Linux)
To use multiple streams as separate cameras:

1.  **Create multiple loopback devices:**
    ```bash
    sudo modprobe -r v4l2loopback
    sudo modprobe v4l2loopback devices=2 exclusive_caps=1 video_nr=10,11 card_label="VirtualCam1,VirtualCam2" max_buffers=2 max_openers=10
    ```

2.  **Run separate instances:**
    ```bash
    # Terminal 1
    python virtualWebcam.py --port 8000 --device /dev/video10

    # Terminal 2
    python virtualWebcam.py --port 8001 --device /dev/video11
    ```

## Troubleshooting

*   **"Device not found" or "No v4l2 loopback device found":**
    *   **Linux:** Ensure you ran the `sudo modprobe ...` command. Check `ls -l /dev/video*`.
    *   **Windows:** Ensure OBS Virtual Camera is installed and active.

*   **Camera not showing in Chrome/Zoom:**
    *   **Linux:** This is usually because `exclusive_caps=1` was missing when loading the module. Unload and reload the module with the correct parameters.
    *   **General:** Ensure the script is running *before* you open the browser/app.

*   **Green/Garbled Video:**
    *   The script uses `YUYV` format for maximum compatibility. If you see issues, try changing the pixel format in the script to `PixelFormat.RGB` (though this is less compatible with some apps).
