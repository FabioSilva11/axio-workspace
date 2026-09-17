#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Servidor proxy intermediário para API Axion.
Captura e registra todo o tráfego entre o app e a API real.

API Real: https://api-ia.axion-ide.online/v1
API Key: REDACTED_SET_ENV_VAR

Este servidor atua como intermediário, capturando requests e responses.
"""
import os
import json
import re
import sys
import time
import requests
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8090
HOST = "0.0.0.0"  # Escuta em todas as interfaces (permite conexões externas)

# Configuração da API externa
EXTERNAL_API_URL = "https://api-ia.axion-ide.online/v1"
EXTERNAL_API_KEY = os.environ.get("AXION_EXTERNAL_API_KEY", "REDACTED_SET_ENV_VAR")

# Chave de API que o app deve usar para se conectar ao proxy
EXPECTED_API_KEY = "test-key-123"

# Log files para capturar todo o tráfego
LOG_FILE_JSON = "traffic_log.json"
LOG_FILE_TXT = "traffic_log.txt"

STATE = {
    "requests": 0,
    "successful_requests": 0,
    "failed_requests": 0,
    "total_tokens_used": 0,
    "last_request_time": None,
    "traffic_logs": [],
}


def log_traffic(direction, endpoint, headers, body, response_data=None, status_code=None, error=None):
    """Registra todo o tráfego HTTP para análise."""
    timestamp = datetime.now().isoformat()
    log_entry = {
        "timestamp": timestamp,
        "direction": direction,  # "REQUEST" ou "RESPONSE"
        "endpoint": endpoint,
        "headers": dict(headers) if headers else {},
        "body": body,
    }
    
    if response_data is not None:
        log_entry["response"] = response_data
    if status_code is not None:
        log_entry["status_code"] = status_code
    if error is not None:
        log_entry["error"] = str(error)
    
    STATE["traffic_logs"].append(log_entry)
    
    # Formata para texto legível
    txt_log = f"\n{'='*100}\n"
    txt_log += f"[{timestamp}] {direction}\n"
    txt_log += f"{'='*100}\n"
    txt_log += f"Endpoint: {endpoint}\n"
    
    if direction == "REQUEST":
        txt_log += f"\n--- HEADERS ---\n"
        if headers:
            for key, value in dict(headers).items():
                # Esconde parcialmente a API key por segurança
                if key == "Authorization" and "Bearer" in str(value):
                    txt_log += f"{key}: Bearer sk-...{str(value)[-20:]}\n"
                else:
                    txt_log += f"{key}: {value}\n"
        
        txt_log += f"\n--- BODY ---\n"
        if body:
            txt_log += json.dumps(body, indent=2, ensure_ascii=False)
        else:
            txt_log += "(vazio)"
        txt_log += "\n"
        
    elif direction == "RESPONSE":
        txt_log += f"\n--- STATUS ---\n"
        txt_log += f"HTTP {status_code}\n"
        
        if error:
            txt_log += f"\n--- ERRO ---\n"
            txt_log += f"{error}\n"
        else:
            txt_log += f"\n--- RESPONSE HEADERS ---\n"
            if headers:
                for key, value in dict(headers).items():
                    txt_log += f"{key}: {value}\n"
            
            txt_log += f"\n--- RESPONSE BODY ---\n"
            if response_data == "[STREAMING]":
                txt_log += "[STREAMING RESPONSE - dados enviados em chunks]\n"
            elif response_data:
                txt_log += json.dumps(response_data, indent=2, ensure_ascii=False)
            else:
                txt_log += "(vazio)"
            txt_log += "\n"
    
    txt_log += f"{'='*100}\n"
    
    # Imprime no console
    print(txt_log)
    
    # Salva no arquivo JSON
    try:
        with open(LOG_FILE_JSON, 'w', encoding='utf-8') as f:
            json.dump(STATE["traffic_logs"], f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"Erro ao salvar log JSON: {e}")
    
    # Salva no arquivo TXT (append)
    try:
        with open(LOG_FILE_TXT, 'a', encoding='utf-8') as f:
            f.write(txt_log)
    except Exception as e:
        print(f"Erro ao salvar log TXT: {e}")


def forward_to_external_api(path, headers, body, stream=False):
    """Encaminha a requisição para a API externa e retorna a resposta."""
    url = EXTERNAL_API_URL + path
    
    # Prepara headers para a API externa
    forward_headers = {
        "Authorization": f"Bearer {EXTERNAL_API_KEY}",
        "Content-Type": "application/json",
    }
    
    # Log do request
    log_traffic("REQUEST", url, forward_headers, body)
    
    try:
        if stream:
            # Para requisições com stream
            response = requests.post(
                url,
                headers=forward_headers,
                json=body,
                stream=True,
                timeout=60
            )
            response.raise_for_status()
            
            log_traffic("RESPONSE", url, response.headers, None, 
                       response_data="[STREAMING]", status_code=response.status_code)
            
            return response, None
        else:
            # Para requisições normais
            response = requests.post(
                url,
                headers=forward_headers,
                json=body,
                timeout=60
            )
            response.raise_for_status()
            
            response_json = response.json()
            log_traffic("RESPONSE", url, response.headers, None, 
                       response_data=response_json, status_code=response.status_code)
            
            # Atualiza estatísticas
            if "usage" in response_json:
                STATE["total_tokens_used"] += response_json["usage"].get("total_tokens", 0)
            STATE["successful_requests"] += 1
            
            return None, response_json
            
    except requests.exceptions.RequestException as e:
        error_msg = str(e)
        log_traffic("RESPONSE", url, None, None, error=error_msg, status_code=getattr(e.response, 'status_code', 500))
        STATE["failed_requests"] += 1
        
        # Tenta extrair mensagem de erro da resposta
        try:
            if hasattr(e, 'response') and e.response is not None:
                error_json = e.response.json()
                return None, {"error": error_json}
        except:
            pass
            
        return None, {"error": {"message": error_msg, "type": "proxy_error"}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[proxy] %s\n" % (fmt % args))

    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_OPTIONS(self):
        """Handle CORS preflight requests."""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            health_data = {
                "status": "ok",
                "proxy": "active",
                "external_api": EXTERNAL_API_URL,
                "stats": {
                    "total_requests": STATE["requests"],
                    "successful": STATE["successful_requests"],
                    "failed": STATE["failed_requests"],
                    "total_tokens": STATE["total_tokens_used"],
                },
                "log_file": LOG_FILE_JSON,
                "logs_count": len(STATE["traffic_logs"]),
            }
            self._send(200, json.dumps(health_data, ensure_ascii=False, indent=2))
            
        elif self.path == "/logs":
            # Endpoint para visualizar os logs capturados
            self._send(200, json.dumps(STATE["traffic_logs"], ensure_ascii=False, indent=2))
            
        elif self.path == "/v1/models":
            # Valida a API key do cliente
            auth_header = self.headers.get("Authorization", "")
            if not auth_header.replace("Bearer ", "").strip() == EXPECTED_API_KEY:
                self._send(401, json.dumps({"error": {"message": "Invalid API key", "type": "invalid_request_error"}}))
                return
            
            # Encaminha para a API externa
            try:
                response = requests.get(
                    f"{EXTERNAL_API_URL}/models",
                    headers={"Authorization": f"Bearer {EXTERNAL_API_KEY}"},
                    timeout=10
                )
                log_traffic("REQUEST", f"{EXTERNAL_API_URL}/models", 
                          {"Authorization": f"Bearer {EXTERNAL_API_KEY}"}, None)
                log_traffic("RESPONSE", f"{EXTERNAL_API_URL}/models", response.headers, 
                          None, response_data=response.json(), status_code=response.status_code)
                
                self._send(response.status_code, response.text)
            except Exception as e:
                error_msg = str(e)
                log_traffic("RESPONSE", f"{EXTERNAL_API_URL}/models", None, None, 
                          error=error_msg, status_code=500)
                self._send(500, json.dumps({"error": {"message": error_msg}}))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        STATE["requests"] += 1
        STATE["last_request_time"] = datetime.now().isoformat()
        
        # Endpoint de controle local (não proxy)
        if self.path == "/control":
            try:
                length = int(self.headers.get("Content-Length", 0))
                cmd = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            except Exception:
                self._send(400, json.dumps({"error": "JSON invalido"}))
                return
            
            if cmd.get("reset"):
                STATE["traffic_logs"].clear()
                STATE.update({
                    "requests": 0,
                    "successful_requests": 0,
                    "failed_requests": 0,
                    "total_tokens_used": 0,
                })
                
            if cmd.get("clear_logs"):
                STATE["traffic_logs"].clear()
                
            self._send(200, json.dumps({"ok": True, "state": STATE}, ensure_ascii=False))
            return
        
        # Valida a API key do cliente
        auth_header = self.headers.get("Authorization", "")
        client_key = auth_header.replace("Bearer ", "").strip()
        if client_key != EXPECTED_API_KEY:
            log_traffic("REQUEST", self.path, self.headers, None)
            log_traffic("RESPONSE", self.path, None, None, 
                       response_data={"error": {"message": "Invalid API key - use: test-key-123"}},
                       status_code=401)
            self._send(401, json.dumps({"error": {"message": "Invalid API key", "type": "invalid_request_error"}}))
            return
        
        # Proxy para /v1/chat/completions
        if self.path != "/v1/chat/completions":
            self._send(404, json.dumps({"error": "endpoint not found"}))
            return
            
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b""
            payload = json.loads(raw.decode("utf-8"))
            messages = payload.get("messages") or []
            
            if not messages:
                self._send(400, json.dumps({"error": {"message": "messages obrigatorios"}}))
                return
                
        except Exception as e:
            self._send(400, json.dumps({"error": {"message": f"JSON invalido: {str(e)}"}}))
            return

        stream = bool(payload.get("stream"))
        
        # Encaminha para a API externa
        stream_response, json_response = forward_to_external_api(
            "/chat/completions",
            self.headers,
            payload,
            stream=stream
        )
        
        if stream_response:
            # Streaming response
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            
            try:
                for chunk in stream_response.iter_content(chunk_size=None, decode_unicode=False):
                    if chunk:
                        self.wfile.write(chunk)
                        self.wfile.flush()
            except Exception as e:
                print(f"Erro no streaming: {e}")
                
        elif json_response:
            # JSON response
            if "error" in json_response:
                self._send(500, json.dumps(json_response, ensure_ascii=False))
            else:
                self._send(200, json.dumps(json_response, ensure_ascii=False))
        else:
            self._send(500, json.dumps({"error": {"message": "Resposta vazia da API"}}))


if __name__ == "__main__":
    # Limpa o arquivo TXT no início
    try:
        with open(LOG_FILE_TXT, 'w', encoding='utf-8') as f:
            f.write(f"{'='*100}\n")
            f.write(f"SERVIDOR PROXY INTERMEDIÁRIO - LOG DE TRÁFEGO\n")
            f.write(f"Iniciado em: {datetime.now().strftime('%d/%m/%Y %H:%M:%S')}\n")
            f.write(f"{'='*100}\n")
    except Exception as e:
        print(f"Aviso: não foi possível criar arquivo TXT: {e}")
    
    # Obtém o IP local da máquina
    import socket
    hostname = socket.gethostname()
    try:
        local_ip = socket.gethostbyname(hostname)
    except:
        local_ip = "Não detectado"
    
    print(f"\n{'='*100}")
    print("SERVIDOR PROXY INTERMEDIÁRIO AXION")
    print(f"{'='*100}")
    print(f"Servidor iniciado com sucesso!")
    print(f"\nEndereços de acesso:")
    print(f"  Local (este computador):  http://127.0.0.1:{PORT}")
    print(f"  Rede local (seu IP):      http://{local_ip}:{PORT}")
    print(f"  Emulador Android:         http://10.0.2.2:{PORT}")
    print(f"\nAPI Externa:")
    print(f"  Endpoint: {EXTERNAL_API_URL}")
    print(f"  Status: Conectado")
    print(f"\nArquivos de Log:")
    print(f"  JSON: {LOG_FILE_JSON}")
    print(f"  TXT:  {LOG_FILE_TXT}  ⭐ (formato legível)")
    print(f"\nEndpoints disponíveis:")
    print(f"  POST /v1/chat/completions")
    print(f"  GET  /v1/models")
    print(f"  GET  /health")
    print(f"  GET  /logs")
    print(f"  POST /control")
    print(f"{'='*100}")
    print(f"\n🔥 AGUARDANDO CONEXÕES DO APP AXION...")
    print(f"💡 Configure a URL base no app para: http://{local_ip}:{PORT}")
    print(f"📝 Todo o tráfego será capturado em: {LOG_FILE_TXT}\n")
    
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n\n" + "="*100)
        print("Servidor encerrado pelo usuário.")
        print(f"Total de requisições capturadas: {STATE['requests']}")
        print(f"Logs salvos em:")
        print(f"  - {LOG_FILE_JSON}")
        print(f"  - {LOG_FILE_TXT}")
        print("="*100)
        srv.shutdown()
