from gpiozero import TonalBuzzer
from gpiozero.tones import Tone
from time import sleep

buzzer = TonalBuzzer(4)

def tick():
    while(True):
        buzzer.play(Tone(880.0))
        sleep(0.05)
        buzzer.stop()
        sleep(0.5)
tick()