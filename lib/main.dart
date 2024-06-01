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
  int _counter = 0;

  @override
  void initState() {
    super.initState();
    _streamBuilder = StreamBuilder<String>(
      stream: streamFromNative(),
      builder: (context, snapshot) {
        if (snapshot.hasData) {
          return Text(
            '${snapshot.data}',
          );
        } else {
          return const CircularProgressIndicator();
        }
      },
    );
  }

  void _incrementCounter() {
    setState(() {
      _counter++;
    });
  }

  Future<void> _getData() async {
    String? data;
    try {
      final result = await platformMethods.invokeMethod<String>('getData');
      data = result;
      debugPrint(data);
    } on PlatformException catch (e) {
      data = "Failed to get data: '${e.message}'.";
    }
  }

  Stream<String> streamFromNative() {
    return platformEvents
      .receiveBroadcastStream()
      .map((event) => event.toString());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            const Text(
              'You have pushed the button this many times:',
            ),
            Text(
              '$_counter',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            _streamBuilder ?? const CircularProgressIndicator(),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          _incrementCounter();
          _getData();
        },
        tooltip: 'Increment',
        child: const Icon(Icons.add),
      ),
    );
  }
}
