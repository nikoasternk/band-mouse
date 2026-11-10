import interconnect from '@system.interconnect'

export default class WearableBridge {
  constructor() {
    this.connection = interconnect.instance();
    this.messageHandlers = {};
    this.statusListeners = [];
    this.onHandshakeStart = null;

    this.connection.onmessage = (event) => {
      try {
        let raw = (event && event.data !== undefined) ? event.data : event;
        while (typeof raw === 'string') {
          raw = JSON.parse(raw);
        }
        const packet = raw;
        if (!packet || !packet.tag) return;

        if (packet.tag === 'handshake') {
          if (packet.step === 0 && this.onHandshakeStart) {
            this.onHandshakeStart();
          }
          const nextStep = (packet.step || 0) + 1;
          if (nextStep < 3) {
            this.sendPacket({ tag: 'handshake', step: nextStep });
          }
          this.notifyStatus('connected');
        } else if (this.messageHandlers[packet.tag]) {
          this.messageHandlers[packet.tag](packet);
        }
      } catch (e) {}
    };
  }

  setHandshakeStartListener(callback) {
    this.onHandshakeStart = callback;
  }

  addStatusListener(callback) {
    this.statusListeners.push(callback);
    return callback;
  }

  removeStatusListener(callback) {
    this.statusListeners = this.statusListeners.filter(item => item !== callback);
  }

  notifyStatus(status) {
    this.statusListeners.forEach(callback => callback(status));
  }

  registerHandler(tag, callback) {
    this.messageHandlers[tag] = callback;
  }

  sendPacket(payloadObj) {
    const serialized = JSON.stringify(payloadObj);
    try {
      this.connection.send({
        data: serialized,
        fail: () => {
          this.connection.send({ data: payloadObj });
        }
      });
    } catch (e) {
      try {
        this.connection.send({ data: payloadObj });
      } catch (err) {}
    }
  }

  sendRaw(rawStr) {
    try {
      this.connection.send({ data: rawStr });
    } catch (e) {}
  }
}