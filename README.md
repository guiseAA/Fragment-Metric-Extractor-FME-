# Fragment-Metric-Extractor-FME-

The *FME module* receives two input parameters: _URL_ (the GitHub URL of the Class) and _MethodName_ (Name of the method). Then, we use JavaParser to traverse the target method's body (represented by a BlockStmt object) and identify well-defined control structures, such as _if_, _for_, _while_, _do-while_, _switch_, and _try-catch_. These structures were selected because they typically encompass semantically meaningful code blocks, often associated with specific behaviors and relatively self-contained within the method.

            
Once these structures were identified, the corresponding inside block of code was selected while preserving its original form. In other words, the extracted fragments consisted of consecutive lines of code, respecting the boundaries of the control structure and maintaining the internal cohesion of the segment. The extraction aimed not merely to isolate individual statements, but to retain the integrity of the fragment as a logically unified entity potentially suitable for extraction.

Then, two additional filtering rules were applied to eliminate less useful fragments: (1) slices whose number of lines was equal to or greater than that of the original method were discarded, and (2) fragments containing reserved keywords such as _break_ or _continue_ were excluded (when the entire structure is not part of the block), as these may indicate dependencies on external structures or hinder safe refactoring. 
