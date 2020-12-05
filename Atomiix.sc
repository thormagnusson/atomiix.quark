

Atomiix {

  var instruments;
  var audioEngine;
  var listenerPool;
  classvar debuggingOn;

  *setup {| oscPort, oscDestination, debug = false |
    Atomiix.log("Waiting for startup message");

    debuggingOn = debug;
    Atomiix.debug("Debugging is on");

    OSCFunc({| msg |
      var projectPath = msg[1];
      if (~atomiix.notNil, { ~atomiix.free; });
      Atomiix.log("Project path is %".format(projectPath));
      ~atomiix = Atomiix.new.init(projectPath, oscPort, oscDestination);
    }, '/setup', NetAddr("localhost"), oscPort);
  }

  init {| projectPath, oscInPort, oscOutPort, oscOutHost = "127.0.0.1" |
    var outPort;
    Atomiix.log("Booting...");

    outPort = NetAddr.new(oscOutHost, oscOutPort);

    instruments = AtomiixInstruments.new.init(projectPath);
    audioEngine = AtomiixAudio.new.init(
      instruments.makeInstrDict,
      instruments.makeEffectDict,
      outPort
    );
    this.setupOSC(oscInPort);
  }

  free {
    Atomiix.log("Cleaning up Engine");
    listenerPool.do({arg oscListener; oscListener.free});
    audioEngine.free();
  }

  setupOSC {| oscPort |
    Atomiix.log("Setting up OSC listeners");
    listenerPool = [];

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var values = msg.unfoldOSC();
        var scoreType = values[1];
        switch (scoreType,
          \percussive, { this.playPercussiveScore(values[2..]) },
          \melodic, { this.playMelodicScore(values[2..]) },
          \concrete, { this.playConcreteScore(values[2..]) },
          { Atomiix.error("Unknown score type: %".format(scoreType)) }
        )
      }, '/play/pattern', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var command = msg[1];
        var agentName = msg[2];
        switch (command,
          \free, {audioEngine.freeAgent(agentName)},
          \doze, {audioEngine.dozeAgent(agentName)},
          \wake, {audioEngine.wakeAgent(agentName)},
          \nap, {
            audioEngine.napAgent(agentName, msg[3..])
          }
        )
      }, '/command', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        audioEngine.changeTempo(msg[1], msg[2]);
      }, '/tempo', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var agentName, value;
        agentName = msg[1];
        value = msg[2];
        audioEngine.setAgentAmplitude(agentName, value);
      }, '/agent/amplitude', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var values, agentName, effects;
        values = msg.unfoldOSC();
        agentName = values[1];
        effects = values[2];
        audioEngine.addEffect(agentName, effects);
      }, '/agent/effects/add', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var values, agentName, effects;
        values = msg.unfoldOSC();
        agentName = values[1];
        effects = values[2];
        audioEngine.removeEffect(agentName, effects);
      }, '/agent/effects/remove', NetAddr("localhost"), oscPort)
    );

    listenerPool = listenerPool.add(
      OSCFunc({| msg |
        var time, timeType, repeats, callbackID;
        time = msg[1];
        timeType = msg[2];
        repeats = msg[3];
        callbackID = msg[4];
        audioEngine.registerCallback(time, timeType, repeats, callbackID);
      }, '/callback', NetAddr("localhost"), oscPort)
    );

    Atomiix.log("Listening on port %".format(oscPort));
  }

  playPercussiveScore {| scoreData |
    var agentName, args;
    agentName = scoreData[0];
    args = ();
    args.notes = scoreData[1];
    args.durations = scoreData[2];
    args.instrumentNames = scoreData[3];
    args.sustainArray = scoreData[4];
    args.attackArray = scoreData[5];
    args.panArray = scoreData[6];
    args.quantphase = scoreData[7];
    args.repeats = scoreData[8];
    args.sampleBank = scoreData[9];
    audioEngine.playPercussiveScore(agentName, args);
  }

  playMelodicScore {| scoreData |
    var agentName, args;
    agentName = scoreData[0];
    args = ();
    args.notes = scoreData[1];
    args.durations = scoreData[2];
    args.instrument = scoreData[3];
    args.sustainArray = scoreData[4];
    args.attackArray = scoreData[5];
    args.panArray = scoreData[6];
    args.quantphase = scoreData[7];
    args.repeats = scoreData[8];
    audioEngine.playMelodicScore(agentName, args);
  }

  playConcreteScore {| scoreData |
    var agentName, args;
    agentName = scoreData[0];
    args = ();
    args.pitch = scoreData[1];
    args.amplitudes = scoreData[2];
    args.durations = scoreData[3];
    args.instrument = scoreData[4];
    args.panArray = scoreData[5];
    args.quantphase = scoreData[6];
    args.repeats = scoreData[7];
    audioEngine.playConcreteScore(agentName, args);
  }

  *log { |message|
    "Atomiix ->  %".format(message).postln;
  }

  *debug { |message|
    if (debuggingOn, {
      "DEBUG   ->  %".format(message).postln;
    });
  }

  *error { |message|
    "Error!  ->  %".format(message).postln;
  }

}
