# Moqui Workflow Component

Extendable workflow engine for the [Moqui Framework](https://www.moqui.org).

## Table of Contents

- [Concepts](#concepts)
- [Installation](#installation)
- [Configuration](#configuration)
- [Authors](#authors)
- [License](#license)

## Concepts

A `Workflow` is a set of activities involved in moving from the beginning to the end of a work process. 
Each workflow is linked to a single entity by means of a `WorkflowType`.
When the workflow engine is triggered for a specific entity value it creates a new `WorkflowInstance`.

## Installation

You will be carrying out these steps to install the workflow engine.

* Download the Moqui Framework (optional)
* Download the [myaddons.xml](myaddons.xml) file
* Download the workflow component

### Download the Moqui Framework

In case you don't have the Moqui Framework yet then you can can download it using this command:

```shell
$ git clone https://github.com/moqui/moqui-framework.git
```

### Download the myaddons.xml file

Using **wget**:

```shell
$ cd moqui-framework
$ wget https://raw.githubusercontent.com/netvariant/moqui-workflow/master/myaddons.xml
```

Using **curl**:

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

Configuring the workflow engine involves these tasks.

* Define workflow types
* Design a workflow
* Trigger workflow engine

### Define workflow types

Workflow types are stores in the `moqui.workflow.WorkflowType` entity and must be defined before the workflow engine is used.

You can define a new workflow type in your component seed data as follows:

```shell
$ <moqui.workflow.WorkflowType typeId="WF_EXAMPLE" typeName="Example Workflow" statusTypeId="ExampleStatus" primaryEntityName="moqui.example.Example" primaryViewEntityName="moqui.example.Example" primaryKeyField="exampleId"/>
```

A brief explanation of the workflow type fields can be found in the table below:

| Field Name | Description |
| :--- | :--- |
| typeId | Type primary key |
| typeName | User friendly type name |
| statusTypeId | Allowed statuses for this type of workflow |
| primaryEntityName | Entity used by the workflow engine for write operations |
| primaryViewEntityName | View entity used by the workflow engine for read operations |
| primaryKeyField | Entity primary key field name |

### Design a workflow

> To be completed

### Trigger workflow engine

You can start/stop workflow instances using Moqui services. The workflow engine comes with the following services:

| Service Name | Description |
| :--- | :--- |
| moqui.workflow.WorkflowServices.create#WorkflowInstance | Creates a new workflow instance |
| moqui.workflow.WorkflowServices.start#WorkflowInstance | Starts an existing workflow instance |
| moqui.workflow.WorkflowServices.suspend#WorkflowInstance | Suspends an existing workflow instance |
| moqui.workflow.WorkflowServices.resume#WorkflowInstance | Resumed a suspended workflow instance | 
| moqui.workflow.WorkflowServices.abort#WorkflowInstance | Aborts an active workflow instance | 

In a real life scenario you calling the above services using SECA/EECA rules.

## Authors

This project was build with :heart: by the good fellas at [Netvariant](https://www.netvariant.com).

## License

[![license](http://img.shields.io/badge/license-CC0%201.0%20Universal-blue.svg)](https://github.com/Netvariant/moqui-workflow/blob/master/LICENSE.md)
 
This project is licensed under the CC0 License - see the [LICENSE.md](LICENSE.md) file for details.