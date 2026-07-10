// Required so the QML module builds as a Qt "executable" (a shared library on
// Android). QtQuickView loads the QML content from the Kotlin side; this main() is
// never the app entry point on Android.
#include <QGuiApplication>

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);

    return app.exec();
}
