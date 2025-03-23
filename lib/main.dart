import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(
    const MaterialApp(
      title: 'Audio test',
      home: SafeArea(child: MyApp()),
    ),
  );
}

const methodChannel = 'example.audiotest/data';
const eventChannel = 'example.audiotest/stream';

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
      ),
      backgroundColor: Colors.grey.shade100,
      body: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  static const platformMethods = MethodChannel(methodChannel);
  static const platformEvents = EventChannel(eventChannel);
  StreamBuilder<String>? _streamBuilder;
  bool _recording = false;
  final TextEditingController _controllerFontSize =
      TextEditingController(text: '16');
  final TextEditingController _controllerMinMagnitude =
      TextEditingController(text: '500000000000');
  final TextEditingController _controllerUpdateRate =
      TextEditingController(text: '10');
  final TextEditingController _controllerNumFreqs =
      TextEditingController(text: '2');

  @override
  void initState() {
    super.initState();
    _streamBuilder = StreamBuilder<String>(
      stream: streamFromNative(),
      builder: (context, snapshot) {
        if (snapshot.hasData) {
          double fontSize = 16;
          try {
            fontSize = double.parse(_controllerFontSize.text);
          } on FormatException catch (e) {
            debugPrint(e.message);
          }
          return Text(
            '${snapshot.data}',
            style: TextStyle(fontSize: fontSize, fontFamily: "monospace"),
          );
        } else {
          return const CircularProgressIndicator();
        }
      },
    );
  }

  Future<void> _toggle() async {
    String? data;
    try {
      final result = await platformMethods.invokeMethod<String>(
          'toggle;${_controllerMinMagnitude.text};${_controllerUpdateRate.text};${_controllerNumFreqs.text}');
      data = result;
      debugPrint(data);
      setState(() {
        _recording = !_recording;
      });
    } on PlatformException catch (e) {
      data = "Failed to get data: '${e.message}'.";
    }
  }

  Stream<String> streamFromNative() {
    return platformEvents
        .receiveBroadcastStream()
        .map((event) => event.toString());
  }

  void showAlertDialog(BuildContext context) {
    // Widget okButton = TextButton(
    //   child: const Text("OK"),
    //   onPressed: () {},
    // );

    AlertDialog alert = AlertDialog(
      title: const Text("Options"),
      content: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Container(
            padding: const EdgeInsets.all(8),
            child: TextField(
              controller: _controllerFontSize,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Font size',
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(8),
            child: TextField(
              controller: _controllerMinMagnitude,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Min magnitude',
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(8),
            child: TextField(
              controller: _controllerUpdateRate,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Update rate',
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(8),
            child: TextField(
              controller: _controllerNumFreqs,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Number of frequencies',
              ),
            ),
          ),
        ],
      ),
      // actions: [
      //   okButton,
      // ],
    );

    showDialog(
      context: context,
      builder: (BuildContext context) {
        return alert;
      },
    );
  }

  // TODO:
  // long press button open dialog with inputs for (font,)
  // min sound magnitude to consider, steps to skip update,
  // and number of freqs to list. Pass as part of method string.
  // Also in returned stream show brackets how close to freq for
  // easier reading.

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          Container(
            padding: const EdgeInsets.only(left: 40),
            child: _streamBuilder ?? const CircularProgressIndicator(),
          ),
        ],
      ),
      floatingActionButton: GestureDetector(
        onLongPress: () {
          showAlertDialog(context);
        },
        child: FloatingActionButton(
          onPressed: () {
            _toggle();
          },
          backgroundColor: Colors.black,
          foregroundColor: Colors.white,
          child: Icon(_recording ? Icons.music_note : Icons.music_off),
        ),
      ),
    );
  }
}
