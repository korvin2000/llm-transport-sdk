# Goal: replace stubs with real implementations, implement Vendor codecs, Auth/OAuth network flows and other remaining tasks

## Detailed: implement second phase by expanding the code with real technical implementations instead of fake-implementations, stubs and wrappers and real working transport layer, real api / vendor api and codecs, etc.

## Prerequisites
- Fetch required information and docs from the web of how-to cleanly reliably implement required logic, auth flows, llm transport, codecs for different providers, etc.
- Analyze, make a index and source code features map and use as references baseline and as implementation examples, implementation references: '\examples\' project, especially '\examples\ai\' project.
- Analyze, make a index and source code features map and use as examples and references: partially implemented llm transport and auth java code in project: '\examples2'. It's a part of another bigger project, it's code is large, bloated, partially over-engineered and may be sub-optimal or buggy, so use it carefully, critically review and use only best well-thought parts of it (if needed), making necessary optimizations, clean-up and fixes.
- Use both '\examples\' and '\examples2' as example, reference implementations - to reduce reimplementation and coding overhead, token consumption, sources of working examples and to prove yourself and current implementation against existing ones.

## Code Restrictions and Requirements
- follow current project layout and packages, modules and classes structure. Use well-thought names and tree-like hierarchy based classes and packages organisation, group classes logically in corresponding packages, avoid creating a lot of classes in single package.
- ponytail like code: lazy senior developer - act as lazy seniour software architect and software engineer: Whenever possible, use ready-made classes and solutions, while testing and fixing then to ensure reliability, avoid overengineering, keep project lightweight, easy readable and expandable. Lazy means efficient, not careless. 
- Mix and combine wherever possible, where it helps reducing coding overhead and more effective work: 'compact-java-code' (compact-code) and 'ponytail' skills.
- avoid overengineering, third-level superfluous tasks, excessive and redundant testing of every step
- write modern well-structured and well-thought object oriented code using best design and development patterns, modern java features: streams, lambdas, etc., using clean-code and YAGNI principles, preserving it's readability and reliability.


## To be done / not currently implemented (short list):

Vendor codecs, OAuth network flows, models.dev feed, Gemini caches and custom TLS remain explicit stubs as the next phase. Per-field catalog provenance, account binding of Content.fileRef, and a ScopedValue call context are recorded as deliberate simplifications in the README. models.json data is still illustrative. Nothing was committed; a session summary was appended to progress_and_session_info.md.