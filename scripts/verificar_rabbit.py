"""Prueba productor real en un vhost temporal aislado, sin tocar mensajes de negocio."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/"evidencias-locales"


def run(*args):
    result=subprocess.run(args,cwd=ROOT,capture_output=True,text=True,timeout=300)
    if result.returncode:
        raise RuntimeError("Falló comando: "+args[0]+" "+args[1])
    return result.stdout


def main():
    OUT.mkdir(exist_ok=True)
    infra=ROOT.parent/"digitalfix-infra/mq"
    cfg=json.loads(run("docker","compose","--env-file",str(infra/".env"),"-f",str(infra/"compose.yml"),"config","--format","json"))
    env=cfg["services"]["rabbitmq1"]["environment"]
    auth=base64.b64encode((env["RABBITMQ_DEFAULT_USER"]+":"+env["RABBITMQ_DEFAULT_PASS"]).encode()).decode()
    def api(method,path,body=None):
        req=urllib.request.Request("http://127.0.0.1:15672/api/"+path,method=method,
            data=None if body is None else json.dumps(body).encode(),headers={"Authorization":"Basic "+auth,"Content-Type":"application/json"})
        with urllib.request.urlopen(req,timeout=15) as response:
            data=response.read()
            return json.loads(data) if data else None
    def snapshot():
        queues=[{k:q.get(k) for k in ("name","durable","auto_delete","arguments","node")} for q in api("GET","queues/digitalfix")]
        bindings=api("GET","bindings/digitalfix")
        return json.dumps([sorted(queues,key=lambda x:x["name"]),sorted(bindings,key=lambda x:json.dumps(x,sort_keys=True))],sort_keys=True)
    antes=snapshot()
    sufijo=uuid.uuid4().hex
    vhost="workorders-it-"+sufijo
    usuario="workorders-it-"+sufijo
    clave=secrets.token_urlsafe(32)
    envfile=OUT/"rabbit-pruebas.env"
    creado_vhost=False
    creado_usuario=False
    try:
        api("PUT","vhosts/"+vhost,{}); creado_vhost=True
        api("PUT","users/"+usuario,{"password":clave,"tags":[]}); creado_usuario=True
        api("PUT","permissions/"+vhost+"/"+usuario,{"configure":"^$","write":r"^cmd\.(direct|topic)$","read":"^$"})
        api("PUT","permissions/"+vhost+"/"+env["RABBITMQ_DEFAULT_USER"],{"configure":".*","write":".*","read":".*"})
        for name,tipo in (("cmd.direct","direct"),("cmd.topic","topic")):
            api("PUT","exchanges/"+vhost+"/"+name,{"type":tipo,"durable":True,"auto_delete":False,"internal":False,"arguments":{}})
        api("PUT","queues/"+vhost+"/q.workorders.it",{"durable":True,"auto_delete":False,"arguments":{"x-queue-type":"classic"}})
        for exchange,key in (("cmd.direct","email.send"),("cmd.direct","dispatch.ticket"),("cmd.direct","report.gen"),("cmd.topic","email.*")):
            api("POST","bindings/"+vhost+"/e/"+exchange+"/q/q.workorders.it",{"routing_key":key,"arguments":{}})
        envfile.write_text("\n".join(["RABBITMQ_TEST_VHOST="+vhost,"RABBITMQ_TEST_USERNAME="+usuario,"RABBITMQ_TEST_PASSWORD="+clave,
            "RABBITMQ_OBSERVER_USERNAME="+env["RABBITMQ_DEFAULT_USER"],"RABBITMQ_OBSERVER_PASSWORD="+env["RABBITMQ_DEFAULT_PASS"]])+"\n",encoding="utf-8")
        os.chmod(envfile,0o600)
        with (OUT/"rabbit-real.log").open("w",encoding="utf-8") as log:
            result=subprocess.run(["docker","run","--rm","--network","digitalfix-messaging","--env-file",str(envfile),
                "digitalfix-workorders:pruebas","./mvnw","-B","-Dtest=RabbitRealTests","test"],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=240)
        for line in (OUT/"rabbit-real.log").read_text(encoding="utf-8").splitlines():
            if "Tests run:" in line or "BUILD " in line or "resultado=publicado" in line:
                print(line,flush=True)
        if result.returncode:
            raise RuntimeError("Fallaron pruebas reales; revisar log local")
        if snapshot()!=antes:
            raise RuntimeError("Topología funcional cambió durante la prueba")
        print("OK: productor real con permisos exclusivos de publicación; topología digitalfix sin cambios",flush=True)
    finally:
        envfile.unlink(missing_ok=True)
        # Solo los recursos únicos creados por esta ejecución; nunca el vhost digitalfix.
        if creado_vhost: api("DELETE","vhosts/"+vhost)
        if creado_usuario: api("DELETE","users/"+usuario)


if __name__=="__main__":
    main()
