# Moqui Workflow Component

Extendable workflow engine built using the <a href="https://www.netvariant.com" target="_blank">Moqui Framework</a>.

## Table of Contents

- [Installation](#installation)
- [Configuration](#configuration)
- [Features](#features)
- [Authors](#authors)
- [License](#license)

## Installation

You will be carrying out these steps to install the workflow engine.

* Download the Moqui Framework (optional)
* Download the [myaddons.xml](myaddons.xml) file
* Download the workflow component

### Download the Moqui Framework

In case you don't have the Moqui Framework then you can can download it using this command:

```shell
$ git clone https://github.com/moqui/moqui-framework.git
```

### Download the myaddons.xml file

Download using **wget**:

```shell
$ cd moqui-framework
$ wget https://raw.githubusercontent.com/netvariant/moqui-workflow/master/myaddons.xml
```

Download using **curl**:

```shell
$ cd moqui-framework
$ curl -O https://raw.githubusercontent.com/netvariant/moqui-workflow/master/myaddons.xml
```

If neither command is available then download [myaddons.xml](myaddons.xml) file manually and copy it to the Moqui Framework root directory.

### Download the workflow component

You're all set to download the workflow component, just run this command and you're done!

```shell
$ ./gradlew getComponent -Pcomponent=moqui-workflow
```

## Configuration

> To be completed.

## Features

> To be completed.

## Authors

This project was developed by the good fellows at [Netvariant](https://www.netvariant.com).

## License

[![license](http://img.shields.io/badge/license-CC0%201.0%20Universal-blue.svg)](https://github.com/Netvariant/moqui-workflow/blob/master/LICENSE.md)
 
This project is licensed under the CC0 License - see the [LICENSE.md](LICENSE.md) file for details.