from bluedot.btcomm import BluetoothServer
from time import sleep
import RPi.GPIO as GPIO
import json
import uuid
import os

BTN_APPROVE = 17
BTN_DENY = 27
DB_FILE = "keys_db.json"

GPIO.setmode(GPIO.BCM)
GPIO.setup(BTN_APPROVE, GPIO.IN, pull_up_down=GPIO.PUD_UP)
GPIO.setup(BTN_DENY, GPIO.IN, pull_up_down=GPIO.PUD_UP)

def load_keys():
    """Loads authorized keys from the JSON database file."""
    if os.path.exists(DB_FILE):
        with open(DB_FILE, 'r') as f:
            return json.load(f)
    return []

def save_key(key):
    """Saves a newly generated key to the JSON database."""
    keys = load_keys()
    if key not in keys:
        keys.append(key)
        with open(DB_FILE, 'w') as f:
            json.dump(keys, f)

# Global flag to track if we are waiting for a human to press a button
awaiting_approval = False

def data_received(data):
    global awaiting_approval
    data = data.strip()
    print(f"Received from Bluetooth: {data}")
    
    if data == "REQUEST_KEY":
        print(">>> NEW KEY REQUESTED. Press APPROVE (GPIO 17) or DENY (GPIO 27) button. <<<")
        awaiting_approval = True
        
    elif data.startswith("UNLOCK:"):
        # Extract the key from the message (e.g., "UNLOCK:1234abcd")
        key = data.split(":")[1]
        
        if key in load_keys():
            print(">>> BLUETOOTH ACCESS GRANTED <<<")
            server.send("ACCESS_GRANTED\n")
            # TODO: Add your code here to trigger the servo or relay
        else:
            print(">>> ACCESS DENIED: Invalid Key <<<")
            server.send("ACCESS_DENIED\n")

def button_pressed(channel):
    global awaiting_approval
    
    if awaiting_approval:
        if channel == BTN_APPROVE:
            # Generate a random 8-character string for the new key
            new_key = str(uuid.uuid4())[:8] 
            save_key(new_key)
            print(f"Request APPROVED. Sent key to phone: {new_key}")
            # Send the new key back to the Android phone
            server.send(f"KEY:{new_key}\n")
            
        elif channel == BTN_DENY:
            print("Request DENIED.")
            server.send("DENIED\n")
            
        # Reset the flag after handling the request
        awaiting_approval = False

# Attach hardware interrupts so the Pi reacts instantly to button presses
GPIO.add_event_detect(BTN_APPROVE, GPIO.FALLING, callback=button_pressed, bouncetime=300)
GPIO.add_event_detect(BTN_DENY, GPIO.FALLING, callback=button_pressed, bouncetime=300)

print("Starting Bluetooth Server...")
print("Waiting for Android phone to connect...")

# Start the server. When connected, any data received triggers the data_received function
server = BluetoothServer(data_received)

try:
    # Keep the script running forever
    while True:
        sleep(1)
        
except KeyboardInterrupt:
    print("\nShutting down server...")
    server.stop()
finally:
    # Ensure the GPIO pins are released when the script exits
    GPIO.cleanup()