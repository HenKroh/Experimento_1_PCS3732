from gpiozero import PWMLED
from time import sleep

# Conecte ao pino BCM 18
led = PWMLED(18)

# Teste de diferentes frequências e brilhos
frequencias_teste = [10, 50, 100, 1000] # Em Hz

for freq in frequencias_teste:
    print(f"Testando frequência: {freq}Hz")
    led.frequency = freq
    
    # Ramp-up do duty cycle (0% a 100%)
    for duty in range(0, 101, 10):
        led.value = duty / 100.0
        sleep(0.1)
    
    led.off()
    sleep(1)