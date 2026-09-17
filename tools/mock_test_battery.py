# -*- coding: utf-8 -*-
"""
Mock de bateria de testes para o app Axion (API OpenAI-compativel).
Nao altera outros servidores em tools/. Cenarios:
  - "USE_TOOL read read_file"  -> tool_call read_file {uri:index.html}
  - "USE_TOOL write rewrite_file" -> tool_call rewrite_file (re-emite apos leitura injetada)
  - "crie um projeto three" -> scaffolda um projeto Three.js COMPLETO de teste
    (three-test/index.html, js/main.js, css/style.css, README.md) via tools do app
  - "TRUNCATE" -> resposta cortada (finish_reason=length); "CONTINUE" completa
  - "MULTI_AGENT"/"multiagente" -> plano multiagente
  - /control {"reset":true} | {"fail_429":true} | {"chain":["read_file","ls_dir"]}
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {
    "requests": 0,
    "tool_calls_issued": 0,
    "read_issued": False,
    "write_issued": False,
    "write_reemitted": 0,
    "chain": None,          # lista de tools a emitir em sequencia
    "fail_429": False,
    "truncated": False,
    "builds": 0,            # projetos Three.js completos gerados
    "last_preview": "",
}


def last_user_text(messages):
    for m in reversed(messages):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                c = " ".join(str(p.get("text", "")) for p in c if isinstance(p, dict))
            return c or ""
    return ""


def tool_result_count(messages):
    return sum(1 for m in messages if m.get("role") in ("tool", "function"))


# ------------------------------------------------------------------
# Projeto Three.js de teste (scaffoldado via tools reais do app)
# ------------------------------------------------------------------

THREE_SCENE = """import * as THREE from 'three';

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x101018);

const camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 100);
camera.position.z = 5;

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

const geometry = new THREE.BoxGeometry(1.5, 1.5, 1.5);
const material = new THREE.MeshStandardMaterial({ color: 0x00ff88, metalness: 0.3, roughness: 0.4 });
const cube = new THREE.Mesh(geometry, material);
scene.add(cube);

const light = new THREE.DirectionalLight(0xffffff, 2);
light.position.set(3, 4, 5);
scene.add(light);

function animate() {
  requestAnimationFrame(animate);
  cube.rotation.x += 0.01;
  cube.rotation.y += 0.02;
  renderer.render(scene, camera);
}
animate();

window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
"""

PROJECT_FILES = [
    {
        "uri": "three-test/index.html",
        "new_content": (
            "<!DOCTYPE html>\n<html lang=\"pt-BR\">\n<head>\n"
            "  <meta charset=\"UTF-8\">\n"
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            "  <title>Teste Three.js - Axion</title>\n"
            "  <link rel=\"stylesheet\" href=\"css/style.css\">\n"
            "  <script type=\"importmap\">\n"
            "  { \"imports\": { \"three\": \"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js\" } }\n"
            "  </script>\n</head>\n<body>\n"
            "  <div id=\"hud\">Projeto gerado pelo agente Axion (teste de tools)</div>\n"
            "  <script type=\"module\" src=\"js/main.js\"></script>\n"
            "</body>\n</html>\n"
        ),
    },
    {
        "uri": "three-test/js/main.js",
        "new_content": THREE_SCENE,
    },
    {
        "uri": "three-test/css/style.css",
        "new_content": (
            "* { margin: 0; padding: 0; box-sizing: border-box; }\n"
            "body { overflow: hidden; font-family: sans-serif; }\n"
            "#hud { position: fixed; top: 12px; left: 12px; color: #00ff88;"
            " font-size: 13px; background: rgba(0,0,0,.55); padding: 8px 12px;"
            " border-radius: 8px; z-index: 10; }\n"
        ),
    },
    {
        "uri": "three-test/README.md",
        "new_content": (
            "# Teste Three.js (Axion)\n\n"
            "Projeto minimo gerado automaticamente pelo agente para validar o loop\n"
            "de ferramentas (criacao de pastas, escrita de arquivos e approval).\n\n"
            "## Arquivos\n- index.html\n- js/main.js (cubo girando)\n- css/style.css\n\n"
            "## Como rodar\nAbra index.html por um servidor estatico.\n"
        ),
    },
]


def build_queue():
    STATE["pending_writes"] = [dict(f) for f in PROJECT_FILES]


def next_pending_write():
    q = STATE.get("pending_writes") or []
    return q[0] if q else None


def pop_pending_write():
    q = STATE.get("pending_writes") or []
    if q:
        return q.pop(0)
    return None


def build_summary_text():
    STATE["builds"] = STATE.get("builds", 0) + 1
    uris = ", ".join(f["uri"] for f in PROJECT_FILES)
    return ("Projeto Three.js de teste criado com sucesso. Arquivos: " + uris
            + ". Abra three-test/index.html em um servidor estatico para ver o cubo girando.")


def last_tool_call_name(messages):
    for m in reversed(messages):
        if m.get("role") == "assistant" and m.get("tool_calls"):
            try:
                return m["tool_calls"][0]["function"]["name"]
            except Exception:
                return None
    return ""


def decide(messages):
    text = last_user_text(messages)
    low = text.lower()

    # Re-emissao pos-leitura obrigatoria: a protecao de edicao obsoleta do app
    # injeta um read_file e espera que o modelo re-emita a MESMA edicao depois.
    # Dispara somente se a ultima tool_call foi a leitura injetada (evita loop
    # apos a propria escrita ser aprovada/rejeitada).
    last_name = last_tool_call_name(messages)
    if (STATE.get("write_issued") and STATE.get("write_reemitted", 0) < 12
            and last_name == "read_file"):
        if STATE.get("pending_writes"):
            nxt = pop_pending_write()
            STATE["write_reemitted"] = STATE.get("write_reemitted", 0) + 1
            return ("tool", "rewrite_file", json.dumps(nxt), None)
        STATE["write_reemitted"] = STATE.get("write_reemitted", 0) + 1
        args = {
            "uri": "index.html",
            "new_content": "<!DOCTYPE html>\n<html><body><h1>ATUALIZADO PELO MOCK</h1>"
                           "<p>Escrita de teste da bateria.</p></body></html>",
        }
        return ("tool", "rewrite_file", json.dumps(args), None)

    # Chain armado via /control: emite as tools em sequencia e depois resume
    if STATE.get("chain"):
        remaining = STATE["chain"]
        nxt = remaining[0]
        if nxt == "read_file":
            args = {"uri": "index.html"}
        elif nxt == "ls_dir":
            args = {"uri": "."}
        else:
            args = {}
        return ("tool", nxt, json.dumps(args), lambda: STATE["chain"].__setitem__(slice(None), remaining[1:]))

    if "use_tool" in low and "write" in low and not STATE["write_issued"]:
        # se ja houve resultado de tool na conversa (leitura injetada), re-emite normalmente:
        # o FileChangeTracker exige que a edicao seja regenerada apos a leitura obrigatoria.
        STATE["write_issued"] = True
        args = {
            "uri": "index.html",
            "new_content": "<!DOCTYPE html>\n<html><body><h1>ATUALIZADO PELO MOCK</h1>"
                           "<p>Escrita de teste da bateria.</p></body></html>",
        }
        return ("tool", "rewrite_file", json.dumps(args), None)

    if "use_tool" in low and ("read" in low or "chain" in low) and not STATE["read_issued"]:
        STATE["read_issued"] = True
        return ("tool", "read_file", json.dumps({"uri": "index.html"}), None)

    # Cenario Three.js: scaffold de projeto completo via tools do app.
    if "three" in low and ("crie" in low or "criar" in low or "projeto" in low):
        if not STATE.get("pending_writes"):
            build_queue()
        nxt = pop_pending_write()
        if nxt is not None:
            STATE["write_issued"] = True
            return ("tool", "rewrite_file", json.dumps(nxt), None)
        return ("text", build_summary_text(), "stop", None)

    if "truncate" in low:
        STATE["truncated"] = True
        return ("text", "Esta resposta foi cortada propositadamente no meio de uma frase sobre o", "length", None)

    if STATE.get("truncated") and ("continue" in low or "continuar" in low):
        STATE["truncated"] = False
        return ("text", "sistema de arquivos do projeto. Continuacao recebida com sucesso apos o truncamento.", "stop", None)

    if "multi_agent" in low or "multiagente" in low:
        return ("text", "Plano multiagente: 1) analisar 2) implementar 3) revisar.", "stop", None)

    return ("text", "Echo do mock: mensagem recebida com sucesso.", "stop", None)


def split_tool_chunks(name, arguments):
    half = max(1, len(arguments) // 2)
    return [
        {"index": 0, "id": "call_battery_1", "type": "function",
         "function": {"name": name, "arguments": arguments[:half]}},
        {"index": 0, "id": "call_battery_1", "type": "function",
         "function": {"name": None, "arguments": arguments[half:]}},
    ]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._json(200, {"status": "ok", "state": STATE})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        if self.path.startswith("/control"):
            try:
                req = json.loads(raw.decode("utf-8"))
            except Exception:
                self._json(400, {"error": "JSON invalido"})
                return
            if req.get("reset"):
                STATE.update({"requests": 0, "tool_calls_issued": 0, "read_issued": False,
                              "write_issued": False, "write_reemitted": 0, "chain": None,
                              "fail_429": False, "truncated": False, "builds": 0,
                              "pending_writes": None, "last_preview": ""})
            if "fail_429" in req:
                STATE["fail_429"] = bool(req["fail_429"])
            if "chain" in req:
                STATE["chain"] = list(req["chain"]) if req["chain"] else None
            self._json(200, {"ok": True, "state": STATE})
            return
        if not self.path.startswith("/v1/chat/completions"):
            self._json(404, {"error": "not found"})
            return

        STATE["requests"] += 1
        if STATE["fail_429"]:
            STATE["fail_429"] = False
            self._json(429, {"error": {"message": "rate limit (mock)", "type": "rate_limit_error"}})
            return

        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._json(400, {"error": "JSON invalido"})
            return
        messages = req.get("messages", [])
        STATE["last_preview"] = str(messages[-1])[:200] if messages else ""
        stream = bool(req.get("stream"))
        decision = decide(messages)

        if decision[0] == "tool":
            _, name, args, after = decision
            STATE["tool_calls_issued"] += 1
            if stream:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                base = {"id": "chatcmpl_battery", "object": "chat.completion.chunk",
                        "created": int(time.time()), "model": req.get("model", "axion-mock")}
                chunks = []
                chunks.append({"choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}]})
                for tc in split_tool_chunks(name, args):
                    chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": [tc]}, "finish_reason": None}]})
                chunks.append({"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]})
                for c in chunks:
                    c.update(base)
                    self.wfile.write(b"data: " + json.dumps(c).encode("utf-8") + b"\n\n")
                self.wfile.write(b"data: [DONE]\n\n")
            else:
                self._json(200, {
                    "id": "chatcmpl_battery", "object": "chat.completion",
                    "created": int(time.time()), "model": req.get("model", "axion-mock"),
                    "choices": [{"index": 0, "message": {
                        "role": "assistant", "content": None,
                        "tool_calls": [{"id": "call_battery_1", "type": "function",
                                        "function": {"name": name, "arguments": args}}],
                    }, "finish_reason": "tool_calls"}],
                })
            if after:
                after()
            return

        _, text, finish, after = decision
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            base = {"id": "chatcmpl_battery", "object": "chat.completion.chunk",
                    "created": int(time.time()), "model": req.get("model", "axion-mock")}
            words = text.split(" ")
            for i, w in enumerate(words):
                piece = (w + " ") if i < len(words) - 1 else w
                c = {"choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}]}
                c.update(base)
                self.wfile.write(b"data: " + json.dumps(c, ensure_ascii=False).encode("utf-8") + b"\n\n")
            c = {"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]}
            c.update(base)
            self.wfile.write(b"data: " + json.dumps(c).encode("utf-8") + b"\n\n")
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            self._json(200, {
                "id": "chatcmpl_battery", "object": "chat.completion",
                "created": int(time.time()), "model": req.get("model", "axion-mock"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                             "finish_reason": finish}],
            })


if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
    print("Mock bateria na porta %d" % port)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
