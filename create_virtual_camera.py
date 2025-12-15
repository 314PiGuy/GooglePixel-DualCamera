import cv2
import pyvirtualcam
import numpy as np
import time
import sys
import argparse



def start_stream(url, device=None):
    print(f"Connecting to {url}...")
    cap = cv2.VideoCapture(url)

    if not cap.isOpened():
        print("Error: Could not open video stream. Make sure the app is running and ADB forward is set.")
        return

    # Fetch stream properties
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    
    if fps <= 0 or np.isnan(fps):
        fps = -1

    # Ensure dimensions are even (required for YUV formats)
    width = width if width % 2 == 0 else width - 1
    height = height if height % 2 == 0 else height - 1

    print(f"Stream detected: {width}x{height} @ {fps} FPS")

    try:
        # pyvirtualcam will automatically find the first available loopback device i hope
        with pyvirtualcam.Camera(width=width, height=height, fps=int(fps), fmt=pyvirtualcam.PixelFormat.YUYV, device=device) as cam:
            print(f"Virtual camera started: {cam.device}")
            
            while True:
                ret, frame = cap.read()
                
                if not ret:
                    print("Frame read failed. Attempting to reconnect...")
                    break

                if frame.shape[1] != width or frame.shape[0] != height:
                    frame = cv2.resize(frame, (width, height))


                cam.send(cv2.cvtColor(frame, cv2.COLOR_BGR2YUV_YUYV))
                
                cam.sleep_until_next_frame()

    except KeyboardInterrupt:
        print("\nStopping...")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        cap.release()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Forward a stream into a virtual camera")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--device", help="Virtual camera device path (e.g., /dev/video10)", default=None)
    args = parser.parse_args()
    stream_url = f"http://{args.host}:{args.port}"
    while True:
        start_stream(stream_url, args.device)
        time.sleep(2)
