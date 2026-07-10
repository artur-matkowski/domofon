cmake_minimum_required(VERSION 3.16)

# The target name and the qt_add_qml_module URI together determine the package of the
# Kotlin/Java wrapper class the Qt Gradle Plugin generates for each .qml file:
#   org.qtproject.example.<target>.<Uri>.<QmlFileName>
# Changing either renames the import in MainActivity.kt.
project(domofon VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Quick)

qt_standard_project_setup(REQUIRES 6.8)

qt_add_executable(domofon
    main.cpp
)

qt_add_qml_module(domofon
    URI DomofonQml
    QML_FILES Main.qml
)

target_link_libraries(domofon
    PRIVATE Qt6::Quick
)

install(TARGETS domofon
    BUNDLE DESTINATION .
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)
