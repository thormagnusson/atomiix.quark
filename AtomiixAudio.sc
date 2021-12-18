

AtomiixAudio {

  var proxyspace;
  var agentDict;
  var effectsDict;
  var instrumentDict;
  var numChan;
  var oscOutPort;

  init{| instrDict, fxDict, outPort, numChannels = 2 |
    TempoClock.default.tempo = 120/60;
    proxyspace = ProxySpace.new.know_(true);
    agentDict = IdentityDictionary.new;
    instrumentDict = instrDict;
    effectsDict = fxDict;
    oscOutPort = outPort;
    numChan = numChannels;
  }

  setAgent{| agentName |
    if(agentDict[agentName].isNil, {
      // 1st = effectRegistryDict, 2nd = scoreInfoDict
      agentDict[agentName] = [(), ().add(\amp->0.5).add(\playstate -> false)];
    });
    ^agentDict[agentName];
  }

  actionAgent{| agentName, action |
    if (agentDict[agentName].notNil, {
      action.value(agentName, agentDict[agentName]);
    }, {
      Atomiix.error("No agent named %".format(agentName));
    });
  }

  registerCallback{| time, timeType, repeats, callbackID |
    {
      repeats.do({| num |
        if(timeType == \beats, {
          (time * TempoClock.default.tempo).wait;
        },{
          time.wait;
        });
        {
          Atomiix.debug("Triggering callback %".format(callbackID));
          this.actionCallback(callbackID, (repeats - 1) - num);
        }.defer;
      });
    }.fork(TempoClock.new)
  }

  actionCallback{| callbackID, remaining |
    oscOutPort.sendMsg("/callback", callbackID, remaining);
  }

  changeTempo{| newTempo, glide |
    if(glide.notNil, {
      TempoClock.default.sync(newTempo/60, glide);
    }, {
      TempoClock.default.tempo = newTempo/60;
    });
    Atomiix.log("Setting tempo to %".format(newTempo));
  }
  
  cmdPeriod {
    CmdPeriod.hardRun;
  }

  freeAgent{| agentName |
    this.actionAgent(agentName, {| agentName |
      Atomiix.log("Freeing agent %".format(agentName));
      agentDict[agentName][1].playstate = false;
      proxyspace[agentName].clear;
      agentDict[agentName] = nil;
      oscOutPort.sendMsg("/agent/state", agentName, "stopped");
    });
  }

  dozeAgent{| agentName |
    this.actionAgent(agentName, {| name |
      Atomiix.log("Dozing agent %".format(name));
      agentDict[name][1].playstate = false;
      proxyspace[name].stop;
      oscOutPort.sendMsg("/agent/state", agentName, "sleeping");
    });
  }

  wakeAgent{| agentName |
    this.actionAgent(agentName, {| agentName |
      Atomiix.log("Waking agent %".format(agentName));
      agentDict[agentName][1].playstate = true;
      proxyspace[agentName].play;
      oscOutPort.sendMsg("/agent/state", agentName, "playing");
    });
  }

  napAgent{| agentName, args |
    var time = args[0];
    var timeType = args[1];
    var repeats = args[2];
    var napDuration = if(timeType == \beats, {
      time * TempoClock.default.tempo;
    }, {
      time;
    });
    this.actionAgent(agentName, {| agentName, agent |
      if (agentDict[agentName][1].playstate, {
        {
          (repeats * 2).do({| num |
            if (agentDict[agentName][1].playstate, {
              proxyspace[agentName].objects[0].array[0].mute;
              agentDict[agentName][1].playstate = false;
              Atomiix.log("Dozing agent %".format(agentName));
              oscOutPort.sendMsg("/agent/state", agentName, "sleeping");
            }, {
              proxyspace[agentName].objects[0].array[0].unmute;
              agentDict[agentName][1].playstate = true;
              Atomiix.log("Waking agent %".format(agentName));
              oscOutPort.sendMsg("/agent/state", agentName, "playing");
            });
            napDuration.wait;
          });
        }.fork(TempoClock.new)
      }, {
        Atomiix.error("Agent % is already sleeping".format(agentName));
      });
    });
  }

  setAgentAmplitude{| agentName, amplitude |
    this.actionAgent(agentName, {| agentName, agent |
      agent[1].amp = amplitude.clip(0, 2);
      if(agent[1].mode == \concrete, {
        Pdef(agentName).set(\amp, agent[1].amp);
      });
      Atomiix.log("Changing % amplitude to %".format(agentName, agent[1].amp));
    });
  }

  addEffect{| agentName, effects |
    this.actionAgent(agentName, {| agentName, agent |
      var agentFX = agent[0];
      effects.do({|effect|
        var fx;
        if(agentFX[effect.asSymbol].isNil, {
          // add 1 (the source is 1)
          agentFX[effect.asSymbol] = agentFX.size+1;

          fx = effectsDict[effect.asSymbol];
          if(fx.notNil, {
            Atomiix.log("Adding effect % to %".format(effect, agentName));
            proxyspace[agentName][agentFX.size] = \filter -> fx;
          }, {
            Atomiix.log("No effect named %".format(effect));
          });
        });
      });
    });
  }

  removeEffect{| agentName, effects |
    this.actionAgent(agentName, {| agentName, agent |
      var agentFX = agent[0];

      if (effects.isNil, {
        // remove all effects (10 max) (+1 as 0 is Pdef)
        10.do({arg i; proxyspace[agentName][i+1] =  nil });
        agentFX.clear;
      }, {
        effects.do({|effect|
          if (agentFX[effect.asSymbol].notNil, {
            // TODO should this handle the gaps it creates?
            Atomiix.log("Removing effect % from %".format(effect, agentName));
            proxyspace[agentName][(agentFX[effect.asSymbol]).clip(1,10)] =  nil;
            agentFX.removeAt(effect.asSymbol);
          });
        });
      });
    });
  }

  agentFinished{| agentName |
    Atomiix.log("Agent % has finished playing".format(agentName));
    oscOutPort.sendMsg("/finished", agentName);
  }

  createFinishingSeq{| agentName, durationArray, repeats |
    if (repeats != inf, {
      var events = repeats * durationArray.size;
      var durations = Pseq(durationArray, inf).asStream;
      ^Pfunc{
        events = events - 1;
        if (events < 0, {
          Atomiix.debug("Agent % has finished repeats".format(agentName));
          this.agentFinished(agentName);
          nil;
        }, { durations.next() });
      }
    }, {
      ^Pseq(durationArray, repeats);
    });
  }

  createAttackSeq{| agent, attackArray |
    var attacks = Pseq(attackArray, inf).asStream;
    ^Pfunc{ attacks.next() * agent[1].amp}
  }

  playPercussiveScore{| agentName, scoreInfo |
    var agent, instruments;

    agent = this.setAgent(agentName);

    Atomiix.log("Playing percussive score on agent %".format(agentName));

    // trick to free if the agent was { instr (Pmono is always on)
    if(agent[1].mode == \concrete, {
      Pdef(agentName).clear;
    });

    instruments = scoreInfo.instrumentNames.collect({|instr| instrumentDict[instr.asSymbol] });

    agent[1].mode = \percussive;
    agent[1].notes = scoreInfo.notes;
    agent[1].durations = scoreInfo.durations;
    agent[1].instrumentNames = scoreInfo.instrumentNames;
    agent[1].instruments = instruments;
    agent[1].sustainArray = scoreInfo.sustainArray;
    agent[1].attackArray = scoreInfo.attackArray;
    agent[1].panArray = scoreInfo.panArray;
    agent[1].quantphase = scoreInfo.quantphase;
    agent[1].repeats = scoreInfo.repeats;

    if (agent[1].durations.size > 0, {
      Pdef(agentName, Pbind(
        \instrument, Pseq(instruments, inf), 
        \midinote, Pseq(scoreInfo.notes, inf), 
        \dur, this.createFinishingSeq(agentName, scoreInfo.durations, scoreInfo.repeats),
        \amp, this.createAttackSeq(agent, scoreInfo.attackArray),
        \sustain, Pseq(scoreInfo.sustainArray, inf),
        \pan, Pseq(scoreInfo.panArray, inf)
      ));
    }, {
      Pdef(agentName).clear;
    });

    if(proxyspace[agentName][0].isNil, {
      proxyspace[agentName].quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
      proxyspace[agentName][0] = Pdef(agentName);
    },{
      Pdef(agentName).quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
    });

    proxyspace[agentName].play;
    agent[1].playstate = true;
    oscOutPort.sendMsg("/agent/state", agentName, "playing");
    agent;
  }

  playMelodicScore {| agentName, scoreInfo |
    var agent;

    agent = this.setAgent(agentName);

    Atomiix.log("Playing melodic score on agent %".format(agentName));

    // trick to free if the agent was { instr (Pmono is always on)
    if(agent[1].mode == \concrete, {
      Pdef(agentName).clear;
    });

    agent[1].mode = \melodic;
    agent[1].notes = scoreInfo.notes;
    agent[1].durations = scoreInfo.durations;
    agent[1].instrument = scoreInfo.instrument;
    agent[1].sustainArray = scoreInfo.sustainArray;
    agent[1].attackArray = scoreInfo.attackArray;
    agent[1].panArray = scoreInfo.panArray;
    agent[1].quantphase = scoreInfo.quantphase;
    agent[1].repeats = scoreInfo.repeats;

    if (agent[1].durations.size > 0, {
      Pdef(agentName, Pbind(
        \instrument, scoreInfo.instrument,
        \type, \note,
        \midinote, Pseq(scoreInfo.notes, inf),
        \dur, this.createFinishingSeq(agentName, scoreInfo.durations, scoreInfo.repeats),
        \sustain, Pseq(scoreInfo.sustainArray, inf),
        \amp, this.createAttackSeq(agent, scoreInfo.attackArray),
        \pan, Pseq(scoreInfo.panArray, inf)
      ));
    }, {
      Pdef(agentName).clear;
    });

    if(proxyspace[agentName][0].isNil, {
      proxyspace[agentName].quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
      proxyspace[agentName][0] = Pdef(agentName);
    },{
      Pdef(agentName).quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
    });

    proxyspace[agentName].play;
    agent[1].playstate = true;
    oscOutPort.sendMsg("/agent/state", agentName, "playing");
    agent
  }

  playConcreteScore{| agentName, scoreInfo|
    var agent;

    agent = this.setAgent(agentName);

    Atomiix.log("Playing concrete score on agent %".format(agentName));

    // due to Pmono not being able to load a new instr, check
    // if it is a new one and free the old one if it is
    if(agent[1].instrument != scoreInfo.instrument, {
      Pdef(agentName).clear;
    });
    if(agent[1].pitch != scoreInfo.pitch, {
      Pdef(agentName).clear;
    });

    agent[1].mode = \concrete;
    agent[1].pitch = scoreInfo.pitch;
    agent[1].amplitudes = scoreInfo.amplitudes;
    agent[1].durations = scoreInfo.durations;
    agent[1].instrument = scoreInfo.instrument;
    agent[1].panArray = scoreInfo.panArray;
    agent[1].quantphase = scoreInfo.quantphase;
    agent[1].repeats = scoreInfo.repeats;

    if (agent[1].durations.size > 0, {
      Pdef(agentName, Pmono(scoreInfo.instrument,
            \dur, this.createFinishingSeq(
                agentName, scoreInfo.durations, scoreInfo.repeats),
            \freq, scoreInfo.pitch.midicps,
            \noteamp, Pseq(scoreInfo.amplitudes, scoreInfo.repeats),
            \pan, Pseq(scoreInfo.panArray, inf)
      ));
    }, {
      Pdef(agentName).clear;
    });


    if(proxyspace[agentName][0].isNil, {
      proxyspace[agentName].quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
      proxyspace[agentName][0] = Pdef(agentName);
    },{
      Pdef(agentName).quant = [scoreInfo.durations.sum, scoreInfo.quantphase, 0, 1];
    });

    // proxyspace quirk: amp set from outside
    Pdef(agentName).set(\amp, agent[1].amp);

    proxyspace[agentName].play;
    agent[1].playstate = true;
    oscOutPort.sendMsg("/agent/state", agentName, "playing");
    agent
  }
}
