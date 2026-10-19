#!/usr/bin/env python3
"""
portal_proxy.py - user-space PS5 Remote Play proxy for PlayStation Portal.

Hypothesis under test: the Portal finds its PS5 via an unauthenticated UDP
discovery broadcast (port 9302) and connects to whichever address answers.
This script answers that broadcast itself, so the Portal connects to THIS
machine, and then relays everything to the real PS5:

    9302/udp  discovery   -> answered from cache, forwarded to PS5
    9295/tcp  session     -> raw TCP relay (init + long-lived ctrl)
    9296/udp  stream      -> UDP relay, startup packet patched to 65/100/200 Mbps
    9297/udp  senkusha    -> UDP relay

No root, no ARP spoofing, no packet capture: plain sockets only.
Mutation logic (offset/XOR) is lifted from atameric/ps5-portal-high-bitrate.

Usage:
    python3 portal_proxy.py --ps5 192.168.1.20 --profile 65
Then disconnect Portal from PS5 and start a fresh connection on Portal.
Ctrl-C to stop.  Reconnecting without the proxy restores normal behaviour.
"""
import argparse, base64, socket, sys, threading, time

# ---------- bitrate mutation (from ps5-portal-high-bitrate) ----------
OFFSET = 334
PATCHES = {'65': b'\x04', '100': bytes.fromhex('03501b0005'), '200': bytes.fromhex('00501b0005')}
PATCH = PATCHES['65']


def readvar(b, p):
    n = 0
    for shift in range(0, 70, 7):
        if p >= len(b):
            raise ValueError('short varint')
        c = b[p]; p += 1; n |= (c & 127) << shift
        if c < 128:
            return n, p
    raise ValueError('bad varint')


def mutate(data):
    """Return (payload, changed). Only touches the narrowly matched BIG startup packet."""
    if len(data) < 32 or data[0] != 0 or data[13] != 0 or data[14] not in (0, 1):
        return data, False
    if data[5:9] != b'\0' * 4 or data[21:26] != b'\0' * 5 or data[26:29] != b'\x08\0\x12':
        return data, False
    if len(data) != int.from_bytes(data[15:17], 'big') + 13:
        return data, False
    try:
        _, pos = readvar(data, 29)
        tag, pos = readvar(data, pos); version, pos = readvar(data, pos)
        if tag != 8 or version != 20:
            return data, False
        while pos < len(data):
            tag, pos = readvar(data, pos)
            if tag & 7 != 2:
                return data, False
            size, pos = readvar(data, pos)
            if tag >> 3 == 3:
                if size != 2296:
                    log(f'startup packet found but LaunchSpec size={size} (expected 2296): NOT patching')
                    return data, False
                lo = OFFSET // 3 * 3; a = pos + lo // 3 * 4
                end = ((OFFSET + len(PATCH) + 2) // 3) * 3
                b = pos + end // 3 * 4
                if b > len(data):
                    return data, False
                raw = bytearray(base64.b64decode(data[a:b], validate=True))
                for i, delta in enumerate(PATCH):
                    raw[OFFSET - lo + i] ^= delta
                return data[:a] + base64.b64encode(raw) + data[b:], True
            pos += size
    except (ValueError, IndexError):
        pass
    return data, False


# ---------- helpers ----------
_lock = threading.Lock()


def log(msg):
    with _lock:
        print(time.strftime('%H:%M:%S'), msg, flush=True)


def local_ip_towards(ps5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((ps5, 9302))
    ip = s.getsockname()[0]
    s.close()
    return ip


# ---------- 9302 discovery ----------
class Discovery(threading.Thread):
    SRCH = b'SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n'

    def __init__(self, ps5, my_ip):
        super().__init__(daemon=True)
        self.ps5, self.my_ip = ps5, my_ip
        self.cache = None
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.bind(('', 9302))
        self.sock.settimeout(1.0)

    def refresh(self, req=None):
        self.sock.sendto(req or self.SRCH, (self.ps5, 9302))

    def run(self):
        last = 0
        while True:
            if time.time() - last > 2:
                self.refresh(); last = time.time()
            try:
                data, (ip, port) = self.sock.recvfrom(4096)
            except (socket.timeout, ConnectionError):  # Windows reports ICMP unreachable here
                continue
            if ip == self.ps5:
                if data.startswith(b'HTTP/1.1'):
                    if self.cache != data:
                        status = data.split(b'\n', 1)[0].decode(errors='replace')
                        log(f'PS5 discovery status: {status}')
                    self.cache = data
                continue
            if ip == self.my_ip:
                continue
            if data.startswith(b'SRCH'):
                if self.cache:
                    self.sock.sendto(self.cache, (ip, port))
                    log(f'SRCH from {ip}:{port} -> answered as PS5 from {self.my_ip}')
                else:
                    log(f'SRCH from {ip}:{port} but no cached PS5 reply yet')
                self.refresh(data)
            elif data.startswith(b'WAKEUP'):
                log(f'WAKEUP from {ip} -> forwarded to PS5')
                self.sock.sendto(data, (self.ps5, 9302))
            else:
                log(f'9302 from {ip}: {data[:40]!r}')


# ---------- 9295 TCP relay ----------
def pipe(src, dst, name):
    try:
        while True:
            buf = src.recv(65536)
            if not buf:
                break
            dst.sendall(buf)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        log(f'TCP {name} closed')


class TcpRelay(threading.Thread):
    def __init__(self, ps5, port=9295):
        super().__init__(daemon=True)
        self.ps5, self.port = ps5, port
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(('', port))
        self.srv.listen(5)

    def run(self):
        while True:
            c, (ip, port) = self.srv.accept()
            log(f'TCP {self.port} connection from {ip}:{port} -> relaying to PS5')
            try:
                up = socket.create_connection((self.ps5, self.port), timeout=10)
                up.settimeout(None)
            except OSError as e:
                log(f'cannot reach PS5:{self.port}: {e}'); c.close(); continue
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=pipe, args=(c, up, f'{ip}->PS5'), daemon=True).start()
            threading.Thread(target=pipe, args=(up, c, f'PS5->{ip}'), daemon=True).start()


# ---------- 9296 / 9297 UDP relay ----------
class UdpRelay(threading.Thread):
    def __init__(self, ps5, port, patch):
        super().__init__(daemon=True)
        self.ps5, self.port, self.patch = ps5, port, patch
        self.down = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.down.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.down.bind(('', port))
        self.flows = {}  # portal (ip,port) -> upstream socket
        self.stats = {'up': 0, 'down': 0, 'patched': 0}

    def upstream_loop(self, up, client):
        while True:
            try:
                data, _ = up.recvfrom(65536)
                self.stats['down'] += len(data)
                self.down.sendto(data, client)
            except ConnectionError:  # transient ICMP unreachable; keep the flow alive
                continue
            except OSError:
                self.flows.pop(client, None)
                return

    def run(self):
        while True:
            try:
                data, client = self.down.recvfrom(65536)
            except ConnectionError:
                continue
            up = self.flows.get(client)
            if up is None:
                up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                up.connect((self.ps5, self.port))
                self.flows[client] = up
                log(f'UDP {self.port} new flow from {client[0]}:{client[1]} -> PS5 via {up.getsockname()[1]}')
                threading.Thread(target=self.upstream_loop, args=(up, client), daemon=True).start()
            if self.patch:
                data, changed = mutate(data)
                if changed:
                    self.stats['patched'] += 1
                    log(f'*** startup packet PATCHED ({self.stats["patched"]}) ***')
            self.stats['up'] += len(data)
            try:
                up.send(data)
            except ConnectionError:
                pass


def stats_loop(relays):
    prev = {r.port: dict(r.stats) for r in relays}
    while True:
        time.sleep(5)
        for r in relays:
            d = r.stats['down'] - prev[r.port]['down']
            if d:
                log(f'UDP {r.port}: PS5->Portal {d*8/5/1e6:.1f} Mbps avg over 5s')
            prev[r.port] = dict(r.stats)


def main():
    global PATCH
    ap = argparse.ArgumentParser()
    ap.add_argument('--ps5', required=True, help='PS5 IPv4 address')
    ap.add_argument('--profile', choices=('65', '100', '200'), default='65')
    ap.add_argument('--no-patch', action='store_true', help='relay only, do not change bitrate (baseline test)')
    a = ap.parse_args()
    PATCH = PATCHES[a.profile]
    my_ip = local_ip_towards(a.ps5)
    log(f'proxy IP {my_ip}, PS5 {a.ps5}, profile {"baseline" if a.no_patch else a.profile + " Mbps"}')
    try:
        disc = Discovery(a.ps5, my_ip)
        tcp = TcpRelay(a.ps5)
        r96 = UdpRelay(a.ps5, 9296, not a.no_patch)
        r97 = UdpRelay(a.ps5, 9297, False)
    except OSError as e:
        log(f'bind failed: {e} (is another Remote Play client or this script already running?)'); sys.exit(1)
    for t in (disc, tcp, r96, r97):
        t.start()
    # PS5 discovery advertises host-request-port:997; relay it too to see if the Portal uses it.
    # Ports < 1024 need root on Android, so this only works on desktop.
    try:
        TcpRelay(a.ps5, 997).start()
    except OSError as e:
        log(f'cannot bind TCP 997 ({e}); only 9295 relayed')
    threading.Thread(target=stats_loop, args=([r96, r97],), daemon=True).start()
    log('READY. Disconnect Portal from PS5, then start a fresh connection on Portal.')
    log('Watch for: SRCH answered -> TCP 9295 connection -> UDP 9296 flow -> startup packet PATCHED')
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log('stopped')


if __name__ == '__main__':
    main()
