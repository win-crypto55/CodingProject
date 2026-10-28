# SwingChat

SwingChat is a small Java desktop chat app. Type a message, press **Send** (or Enter), and Ollama's answer appears in the scrollable chat history.

## What you need

- Java 17 or newer
- Ollama installed and running on this computer
- The `llama3.2:1b` model downloaded in Ollama

The 1B model is selected because it uses less memory and responds more quickly than the 3B model on lower-powered computers.
Basic arithmetic such as `2+3` is answered instantly and exactly by the app. Other questions are sent to Ollama.

## First-time setup

Open PowerShell and run:

```powershell
ollama pull llama3.2:1b
```

Make sure Ollama is running. On Windows, the Ollama app usually runs in the background. If it is not running, open the Ollama app or start its local server:

```powershell
ollama serve
```

Keep that terminal open while using the app. You can check that the model is installed with:

```powershell
ollama list
```

## Start the app

In PowerShell, change to this project folder, compile the Java files, and start SwingChat:

```powershell
Set-Location "C:\Users\compuper 2\Desktop\CodingProject"
javac SwingChat.java SwingChatCheck.java
java SwingChat
```

## Use the chat

1. Type a question or a simple calculation (for example, `2+3`) in the message field at the bottom of the window.
2. Press **Send** or Enter.
3. Wait for the answer to appear in the chat history.
4. Scroll up in the history panel to read earlier messages.

The first answer can take longer while Ollama loads the model. The app asks Ollama to keep the model loaded for 10 minutes and sends only recent conversation context, so follow-up answers should start faster. Chat history stays in the window until the app closes; it is not saved to disk.

## Check the response parser

From the project folder, run:

```powershell
java SwingChatCheck
```

Each check should print `passed`. These checks do not require Ollama to be running.

## Troubleshooting

- **Cannot connect to Ollama:** Open the Ollama app. If needed, start `ollama serve` in a separate terminal and leave it running.
- **Model not found:** Run `ollama pull llama3.2:1b`, then restart SwingChat.
- **Answer takes a long time:** Allow extra time for the first answer while the model loads. Later answers are faster while the model remains loaded. Closing Ollama or waiting longer than 10 minutes can require another load.
- **Request times out:** Check that Ollama is running, try a shorter question, and make sure the computer has enough free memory.
- **Want a different model?** Change the `MODEL` constant in `SwingChat.java` to a model installed in Ollama.

## Project files

- `SwingChat.java` — desktop interface, chat history, Ollama request, and JSON parsing
- `SwingChatCheck.java` — small checks for JSON escaping and Ollama response parsing
