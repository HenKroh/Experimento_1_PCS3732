from gpiozero import TonalBuzzer
from gpiozero.tones import Tone
from time import sleep

buzzer = TonalBuzzer(12)

def tick():
    while(True):
        buzzer.play(Tone(880.0)) # Nota A5 (880Hz)
        sleep(0.05)              # Duração do som curto
        buzzer.stop()
        sleep(0.5)              # Duração do som curto

tick()