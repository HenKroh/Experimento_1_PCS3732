import Keypad
import smbus
from time import sleep
from LCD1602 import CharLCD1602
from gpiozero import TonalBuzzer, DistanceSensor
from gpiozero.tones import Tone
import warnings
warnings.filterwarnings("ignore")

ROWS = 4
COLS = 4
PASSWORD = "123456"
MAX_TRIES = 3

keys = ['1','2','3','A',
        '4','5','6','B',
        '7','8','9','C',
        '*','0','#','D']
rowsPins = [16, 20, 21, 26]
colsPins = [19, 13, 6, 5]


keypad = Keypad.Keypad(keys,rowsPins,colsPins,ROWS,COLS)
keypad.setDebounceTime(100)
lcd1602 = CharLCD1602()
lcd1602.init_lcd()
buzzer = TonalBuzzer(4)
sensor = DistanceSensor(echo=15, trigger=14 ,max_distance=3)

password = ""
blocked = False
closed = True
wrong_tries = 0
    
def loop():
    while(True):
        distance = sensor.distance*100
        if distance >= 30:
            closed = False
        else:
            closed = True
        print('Distance: ', distance,'cm')
        if not closed:
            lcd1602.write(0,0, "Opened!")
        else:
            if len(password) == 0:
                lcd1602.write(0,0, "Closed!")
            key = keypad.getKey()
            if(key != keypad.NULL and key != "*" and key != "#"):
                password+=key
                print ("Pressed Key: %c"%(key))
                lcd1602.write(0, 0,  "Pasword:")
                lcd1602.write(0, 1,  "*"*len(password))
            if(key == "#"):
                if password == PASSWORD:
                    lcd1602.clear()
                    lcd1602.write(0,0, "Welcome!")
                    wrong_tries = 0
                    buzzer.play(Tone(880.0))
                    sleep(0.1)
                    buzzer.stop()
                    sleep(.9)
                else:
                    lcd1602.clear()
                    lcd1602.write(0,0, "Access Denied!")
                    wrong_tries+=1
                    buzzer.play(Tone(440))
                    sleep(1)
                    buzzer.stop()
                if wrong_tries >= MAX_TRIES:
                    for i in range (15,0,-1):
                        lcd1602.clear()
                        lcd1602.write(0,0, "System Blocked!")
                        lcd1602.write(0,1, "Try Again in " + str(i) +"s")
                        sleep(1)
#             sleep(2)
                lcd1602.clear()
                lcd1602.write(0,0, "Closed")
                password=""
        sleep(0.05)
            
if __name__ == '__main__':
    print ("Program is starting ... ")
    try:
        loop()
    except KeyboardInterrupt:
        lcd1602.clear()
        print("Ending program")