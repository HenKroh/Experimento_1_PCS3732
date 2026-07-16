from gpiozero import AngularServo, TonalBuzzer, Button
from gpiozero.tones import Tone
from time import sleep, time

# Configuração de Hardware
servo = AngularServo(18,initial_angle=0, min_angle=0, max_angle=180, min_pulse_width=0.0005, max_pulse_width=0.0025)
buzzer = TonalBuzzer(27)
btn_aumentar = Button(20, bounce_time=0.1) # Pull-up interno ativado por padrão
btn_diminuir = Button(21, bounce_time=0.1)

# Variáveis Globais
bpm = 60 # Começa com 1 batida por segundo
estado_pendulo = 0

def atualizar_bpm(incremento):
    global bpm
    bpm += incremento
    if bpm < 30: bpm = 30
    if bpm > 200: bpm = 200
    print(f"BPM atualizado: {bpm}")

# Callbacks de interrupção (Desafio)
btn_aumentar.when_pressed = lambda: atualizar_bpm(10)
btn_diminuir.when_pressed = lambda: atualizar_bpm(-10)

def loop_metronomo():
    global estado_pendulo
    
    try:
        print(f"Iniciando metrônomo a {bpm} BPM...")
        while True:
            inicio_ciclo = time()
            
            # Executa a ação do metrônomo
            servo.angle = estado_pendulo
            buzzer.play(Tone(880.0))
            sleep(0.05) # Tempo do "tick"
            buzzer.stop()
            
            # Alterna a direção para a próxima batida
            estado_pendulo = 180 if estado_pendulo == 0 else 0
            
            # Calcula o tempo restante para manter a precisão do BPM
            tempo_batida = 60.0 / bpm
            tempo_gasto = time() - inicio_ciclo
            tempo_espera = tempo_batida - tempo_gasto
            
            if tempo_espera > 0:
                sleep(tempo_espera)
                
    except KeyboardInterrupt:
        print("Saindo...")
        servo.detach()
        buzzer.stop()

if __name__ == "__main__":
    loop_metronomo()