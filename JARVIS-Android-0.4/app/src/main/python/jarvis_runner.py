import contextlib
import io
import traceback

def run(source):
    output = io.StringIO()
    scope = {"__name__": "__main__"}
    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            exec(compile(source, "<jarvis>", "exec"), scope, scope)
        text = output.getvalue().strip()
        return text if text else "Ejecución completada sin salida."
    except BaseException:
        text = output.getvalue().strip()
        error = traceback.format_exc(limit=8)
        return (text + "\n" if text else "") + "ERROR:\n" + error
