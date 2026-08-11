"""Serviço da fechadura para Raspberry Pi.

Periférico BLE que fala o mesmo protocolo do app iOS (`ios/SmartLock`) e do
Android: cadastro aprovado por botão físico e desbloqueio por desafio–resposta
com HMAC-SHA256.
"""

__version__ = "1.0"
