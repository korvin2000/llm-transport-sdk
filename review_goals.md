# Document contains user prompt, actual state description and desired goals.

## Short info and short goals
A first implementation prototype / draft is complete, but it needs to be critically reviewed, fixed and project stucture should be revised and improved.
Implementation progress and goals are described in: '\progress_and_session_info.md'
Analyze, understand and critically review, making suggestions actual implementation under: '\llm'

## What I don't like:
Actual project structure is too flat, I'd like to have a more well-thought, deep-nested and hierarchical tree-like project architecture like in good well-designed object oriented projects.
for example in 'net.ai.gate' located 61 java class/interfaces - There are too many classes that are poorly organized hierarchically and are thematically and logically disconnected from one another, which makes it difficult to navigate and understand the code.
Directories / packages: 'google', 'anthropic', 'openai' located directly under 'net.ai.gate' ("\net\ai\gate\"), but should be located and gropped under 'net.ai.gate.vendors' ("\net\ai\gate\vendors") directory.
Exceptions located flat under 'net.ai.gate' should have own subdirectory, as well as tool related classes, options and configuration related classes, metadata and info related and so on. A good practice is not more as 20 classes in one package-  with exceptions to the rule where it is truly necessary to have everything in a single package—where classes perform the same function and it makes no sense to group them any other way.
OAuth related classes located directly under "net.ai.gate.auth", but should have own subdirectory, own package like 'net.ai.gate.auth.oauth'.

## Goals summaries

So please analyze and re-think project structure and suggest a best ,more hierarchical and well ogranized structure and groupping of clasess.
Other task - analyze existing code for any issues and remainig / unfinished tasks, not related to concrete api, protocols and vendor-specific implementation, but related to initial task '\progress_and_session_info.md', in other words what else is missing or incomplete that would allow us to say that the first phase, this prototype and implementation skeleton / implementation draft has been implemented thoroughly and to a high standard?
A next phase after this phase will be implemention of current fake, stub, mock and wrapper classes with real logic, real api or real vendor specific logic.
Don't change anything in the code just yet; analyze, review and evaluate the current project in depth and thoroughly, as an experienced software architect and engineer, to find any issues, unfinished tasks related to the first phase of the prototype, design problems, structure problems, taking my wishes into account and create a well stuctured and well-thought 'review_and_suggestions.md' document, optimized for LLM-readability which will be used to improve, fix and refactor current project as the next step.
Use best most suitable engineering, software architecture and code review skills in order to achieve desired goals, tasks and make in-depth and critical code review, analyze and evalutation and write a document with a description of problems, issues found and solutions, along with information on how to fix them.