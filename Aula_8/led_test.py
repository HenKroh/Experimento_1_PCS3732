from gpiozero import PWMLED
from time import sleep

led = PWMLED(18)

frequencias_teste = [10, 50, 100, 1000]

for freq in frequencias_teste:
    print(f"Testando frequência: {freq}Hz")
    led.frequency = freq
    
    for duty in range(0, 101, 5):
        led.value = duty / 100.0
        sleep(0.1)
    
    led.off()
    sleep(1)