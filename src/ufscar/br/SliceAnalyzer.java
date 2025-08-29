package ufscar.br;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKNotifier;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKMethodResult;





public class SliceAnalyzer{

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: <url-do-arquivo-java> <nomeDoMetodo>");
            return;
        }
        

        String fileUrl = convertToRawUrl(args[0]);
        String methodName = args[1];

        System.out.println("URL do arquivo: " + fileUrl);
        System.out.println("Método: " + methodName);
        


        try {
            String methodCode = extractMethodFromGitHubWithJavaParser(fileUrl, methodName);

            if (methodCode.isEmpty()) {
                System.err.println("Método não encontrado.");
                return;
            }

            String wrapped = "class Temp { " + methodCode + " }";
            CompilationUnit cu = StaticJavaParser.parse(wrapped);
            MethodDeclaration methodAST = cu.findFirst(MethodDeclaration.class).orElseThrow(() -> new Exception("Método não encontrado"));

            Set<String> loopVars = extractLoopControlVariables(methodAST);
            Map<String, Integer> variableUsageFreq = countVariableUsageInMethod(methodAST);
            List<Slice> slices = extractSlices(methodCode, variableUsageFreq,loopVars);

             //Gson gson = new GsonBuilder().setPrettyPrinting().create();
            // Salva o JSON original com todas as informações dos slices
            //FileWriter writer = new FileWriter("method_slices_usage_outside.json");
            //gson.toJson(slices, writer);
            //writer.close();
            //System.out.println("Slices extraídos e salvos em method_slices_usage_outside.json");

            // ===== 2. JSON simplificado de slices =====
            List<SimpleSlice> simpleList = new ArrayList<>();     
            Set<String> seenFragments = new HashSet<>();
            
            for (Slice s : slices) {
                for (String frag : s.slice) {
                    if (!deveExcluirSlice(frag, methodCode) && seenFragments.add(frag.trim())) {
                        simpleList.add(new SimpleSlice(methodCode, frag, s.totalUsageOutsideSlice));
                    }
                }
            }

            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter("simplified_slices.json")) {
                gson.toJson(simpleList, writer);
                System.out.println("JSON simplificado salvo em simplified_slices.json");
            }           
            
            
            // ===== 3. Criação de Temp.java para CK =====
            String tempDirPath = "temp_ck_analysis";
            File tempDir = new File(tempDirPath);
            tempDir.mkdirs();

            String classCode = "class Temp { " + methodCode + " }";
            /*FileWriter writer = new FileWriter(tempDirPath + "/Temp.java");
            writer.write(classCode);
            writer.close();*/
            try (FileWriter writer = new FileWriter(tempDirPath + "/Temp.java")) {
                writer.write(classCode);
            }

            // ===== 4. Execução do CK =====
            List<Map<String, Object>> metricsJson = new ArrayList<>();

            new CK().calculate(tempDirPath, new CKNotifier() {
                @Override
                public void notify(CKClassResult classe) {
                    for (CKMethodResult metodo : classe.getMethods()) {
                        if (metodo.getMethodName().contains(methodName)) {
                            Map<String, Object> metricas = new LinkedHashMap<>();
                            metricas.put("id_column_name",1);                            
                            //metricas.put("methodName", metodo.getMethodName());
                            metricas.put("methodAnonymousClassesQty", metodo.getAnonymousClassesQty());                                        
                            metricas.put("methodAssignmentsQty", metodo.getAssignmentsQty());
                            metricas.put("methodCbo", metodo.getCbo());                            
                            metricas.put("methodComparisonsQty", metodo.getComparisonsQty());                           
                            metricas.put("methodLambdasQty", metodo.getLambdasQty());                         
                            metricas.put("methodLoc", metodo.getLoc());
                            metricas.put("methodLoopQty", metodo.getLoopQty());     
                            metricas.put("methodMathOperationsQty", metodo.getMathOperationsQty());    
                            metricas.put("methodMaxNestedBlocks", metodo.getMaxNestedBlocks());
                            metricas.put("methodNumbersQty", metodo.getNumbersQty());
                            metricas.put("methodParametersQty", metodo.getParametersQty());
                            metricas.put("methodParenthesizedExpsQty", metodo.getParenthesizedExpsQty());                          
                            metricas.put("methodReturnQty", metodo.getReturnQty());
                            metricas.put("methodRfc", metodo.getRfc());     
                            metricas.put("methodStringLiteralsQty", metodo.getStringLiteralsQty());
                            metricas.put("methodSubClassesQty",metodo.getInnerClassesQty());                            
                            metricas.put("methodTryCatchQty", metodo.getTryCatchQty());                            
                            metricas.put("methodUniqueWordsQty", metodo.getUniqueWordsQty());
                            metricas.put("methodVariablesQty", metodo.getVariablesQty());                            
                            metricas.put("methodWmc", metodo.getWmc());
                            //metricas.put("bugFixCount",0);
                            //metricas.put("refactoringsInvolved",0);                            
                            metricsJson.add(metricas);
                        }
                    }
                }
            });

            // ===== 5. Salvamento do JSON com métricas CK =====
            /*try (FileWriter out = new FileWriter("method_metrics.json")) {
                gson.toJson(metricsJson, out);
                System.out.println("Métricas salvas em method_metrics.json");
            }*/
            
            // ========Agora salvando em CSV:
            try (FileWriter csvOut = new FileWriter("method_metrics.csv")) {
                // Cabeçalho
                csvOut.write("id_,methodAnonymousClassesQty,methodAssignmentsQty,methodCbo,methodComparisonsQty,methodLambdasQty,methodLoc,methodLoopQty,methodMathOperationsQty,methodMaxNestedBlocks,methodNumbersQty,methodParametersQty,methodParenthesizedExpsQty,methodReturnQty,methodRfc,methodStringLiteralsQty,methodSubClassesQty,methodTryCatchQty,methodUniqueWordsQty,methodVariablesQty,methodWmc\n");

                for (Map<String, Object> method : metricsJson) {
                	List<String> values = new ArrayList<>();
                    	for (String key : method.keySet()) {
                    		values.add(method.get(key).toString());
                    	}
                    	csvOut.write(String.join(",", values) + "\n");
                }

                System.out.println("Métricas salvas em method_metrics.csv");
            }

            // ===== 6. Limpeza dos arquivos temporários =====
            File tempFile = new File(tempDirPath + "/Temp.java");
            if (tempFile.exists()) tempFile.delete();
            if (tempDir.exists() && tempDir.isDirectory() && tempDir.list().length == 0) {
                tempDir.delete();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class Slice {
        String label;
        String justification;
        List<String> slice;
        List<String> variables;
        Map<String, Integer> variableUsageOutsideSliceCount;
        int totalUsageOutsideSlice;
        double usageDensityOutsideSlice;

        public Slice(String label, String justification, List<String> slice, List<String> variables,
                     Map<String, Integer> variableUsageOutsideSliceCount, int totalUsageOutsideSlice) {
            this.label = label;
            this.justification = justification;
            this.slice = slice;
            this.variables = variables;
            this.variableUsageOutsideSliceCount = variableUsageOutsideSliceCount;
            this.totalUsageOutsideSlice = totalUsageOutsideSlice;
                        
        }

    }

    // Nova classe para o JSON simplificado
    public static class SimpleSlice {
        String methodCode;
        String sourceCodeFragment;
        int totalUsageOutsideSlice;

        public SimpleSlice(String methodCode, String sourceCodeFragment, int totalUsageOutsideSlice) {
            this.methodCode = methodCode;
            this.sourceCodeFragment = sourceCodeFragment;
            this.totalUsageOutsideSlice = totalUsageOutsideSlice;
        }
    }
   
    

    public static String convertToRawUrl(String githubUrl) {
        if (githubUrl == null) return "";
        githubUrl = githubUrl.trim();
        if (githubUrl.matches("https?://(www\\.)?github\\.com/.+?/blob/.+")) {
            return githubUrl
                    .replaceFirst("https?://(www\\.)?github\\.com/", "https://raw.githubusercontent.com/")
                    .replace("/blob/", "/");
        }
        return githubUrl;
    }

    public static String extractMethodFromGitHubWithJavaParser(String rawFileUrl, String shortMethodName) {
        try {
            URL url = new URL(rawFileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            StringBuilder code = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    code.append(line).append("\n");
                }
            }

            CompilationUnit cu = StaticJavaParser.parse(code.toString());

            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            for (MethodDeclaration method : methods) {
                if (method.getNameAsString().equals(shortMethodName)) {
                    return method.toString();
                }
            }

        } catch (Exception e) {
            System.err.println("Erro ao baixar ou processar o arquivo: " + e.getMessage());
        }

        return "";
    }

    public static List<Slice> extractSlices(String methodCode, Map<String, Integer> variableUsageFreq, Set<String> loopVars) throws Exception {
        List<Slice> slices = new ArrayList<>();

        String wrapped = "class Temp { " + methodCode + " }";
        CompilationUnit cu = StaticJavaParser.parse(wrapped);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow(() -> new Exception("Método não encontrado"));

        if (!method.getBody().isPresent()) return slices;
        BlockStmt body = method.getBody().get();

        analyzeBlock(body, slices, variableUsageFreq, loopVars);
        
        System.out.println("Total de slices coletados (antes do filtro): " + slices.size());


        return slices;
    }

    
    private static void analyzeBlock(BlockStmt block, List<Slice> slices, Map<String, Integer> variableUsageFreq, Set<String> loopVars)
    {
        List<Statement> stmts = block.getStatements();

        List<String> currentSliceStatements = new ArrayList<>();
        String currentLabel = null;
        String currentJustification = null;

        for (Statement stmt : stmts) {

            // Debug: imprime o tipo do statement
            //System.out.println("Statement: " + stmt);
            //System.out.println("Tipo: " + stmt.getClass().getSimpleName());

            if (stmt.isIfStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);

                IfStmt ifStmt = stmt.asIfStmt();
                String label = "Bloco condicional IF";
                String justification = "Executa código condicionalmente se a condição for verdadeira";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(ifStmt.toString());
                List<String> vars = extractVariables(ifStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(ifStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();
                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                Statement thenStmt = ifStmt.getThenStmt();
                if (thenStmt.isBlockStmt()) {
                    analyzeBlock(thenStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                } else {
                    List<String> singleStmtSlice = new ArrayList<>();
                    singleStmtSlice.add(thenStmt.toString());
                    List<String> varsThen = extractVariables(thenStmt);
                    Map<String, Integer> outsideCountThen = calculateUsageOutsideSlice(thenStmt, varsThen, variableUsageFreq,loopVars);
                    int totalOutsideThen = outsideCountThen.values().stream().mapToInt(Integer::intValue).sum();
                    slices.add(new Slice("Bloco THEN (único statement)", "Statement único no THEN", singleStmtSlice, varsThen, outsideCountThen, totalOutsideThen));
                }

                ifStmt.getElseStmt().ifPresent(elseStmt -> {
                    if (elseStmt.isBlockStmt()) {
                        analyzeBlock(elseStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                    } else {
                        List<String> elseSlice = new ArrayList<>();
                        elseSlice.add(elseStmt.toString());
                        List<String> varsElse = extractVariables(elseStmt);
                        Map<String, Integer> outsideCountElse = calculateUsageOutsideSlice(elseStmt, varsElse, variableUsageFreq,loopVars);
                        int totalOutsideElse = outsideCountElse.values().stream().mapToInt(Integer::intValue).sum();
                        slices.add(new Slice("Bloco ELSE", "Código executado se condição IF for falsa", elseSlice, varsElse, outsideCountElse, totalOutsideElse));
                    }
                });

            } else if (stmt.isForEachStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);

                ForEachStmt forEachStmt = stmt.asForEachStmt();
                String label = "Bloco de repetição FOR-EACH";
                String justification = "Executa repetidamente para cada elemento de uma coleção";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(forEachStmt.toString());
                List<String> vars = extractVariables(forEachStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(forEachStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();
                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                Statement bodyStmt = forEachStmt.getBody();
                if (bodyStmt.isBlockStmt()) {
                    analyzeBlock(bodyStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                } else {
                    List<String> singleStmtSlice = new ArrayList<>();
                    singleStmtSlice.add(bodyStmt.toString());
                    List<String> varsBody = extractVariables(bodyStmt);
                    Map<String, Integer> outsideCountBody = calculateUsageOutsideSlice(bodyStmt, varsBody, variableUsageFreq,loopVars);
                    int totalOutsideBody = outsideCountBody.values().stream().mapToInt(Integer::intValue).sum();
                    slices.add(new Slice("Corpo FOR-EACH (único statement)", "Statement único no corpo do FOR-EACH", singleStmtSlice, varsBody, outsideCountBody, totalOutsideBody));
                }

            } else if (stmt.isForStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);

                ForStmt forStmt = stmt.asForStmt();
                String label = "Bloco de repetição FOR";
                String justification = "Executa repetidamente com inicialização, condição e incremento";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(forStmt.toString());
                List<String> vars = extractVariables(forStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(forStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();
                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                Statement bodyStmt = forStmt.getBody();
                if (bodyStmt.isBlockStmt()) {
                    analyzeBlock(bodyStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                } else {
                    List<String> singleStmtSlice = new ArrayList<>();
                    singleStmtSlice.add(bodyStmt.toString());
                    List<String> varsBody = extractVariables(bodyStmt);
                    Map<String, Integer> outsideCountBody = calculateUsageOutsideSlice(bodyStmt, varsBody, variableUsageFreq,loopVars);
                    int totalOutsideBody = outsideCountBody.values().stream().mapToInt(Integer::intValue).sum();
                    slices.add(new Slice("Corpo FOR (único statement)", "Statement único no corpo do FOR", singleStmtSlice, varsBody, outsideCountBody, totalOutsideBody));
                }

            } else if (stmt.isWhileStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);

                WhileStmt whileStmt = stmt.asWhileStmt();
                String label = "Bloco de repetição WHILE";
                String justification = "Executa enquanto a condição for verdadeira";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(whileStmt.toString());
                List<String> vars = extractVariables(whileStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(whileStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();
                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                Statement bodyStmt = whileStmt.getBody();
                if (bodyStmt.isBlockStmt()) {
                    analyzeBlock(bodyStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                }

            } else if (stmt.isDoStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);

                DoStmt doStmt = stmt.asDoStmt();
                String label = "Bloco de repetição DO-WHILE";
                String justification = "Executa ao menos uma vez e repete enquanto a condição for verdadeira";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(doStmt.toString());
                List<String> vars = extractVariables(doStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(doStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();
                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                Statement bodyStmt = doStmt.getBody();
                if (bodyStmt.isBlockStmt()) {
                    analyzeBlock(bodyStmt.asBlockStmt(), slices, variableUsageFreq,loopVars);
                }

            }else if (stmt.isTryStmt()) {
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq, loopVars);

                TryStmt tryStmt = stmt.asTryStmt();
                String label = "Bloco TRY";
                String justification = "Bloco que pode lançar exceções, com tratamento de erro opcional";

                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(tryStmt.toString());
                List<String> vars = extractVariables(tryStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(tryStmt, vars, variableUsageFreq,loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();

                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                // Recursivamente analisa o conteúdo do bloco try
                if (tryStmt.getTryBlock().isBlockStmt()) {
                    analyzeBlock(tryStmt.getTryBlock(), slices, variableUsageFreq,loopVars);
                }

                // Também pode analisar blocos catch e finally, se desejar
                       
            } else if (stmt.isSwitchStmt()) {
                // Salva qualquer slice em construção anterior
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq, loopVars);

                SwitchStmt switchStmt = stmt.asSwitchStmt();
                String label = "Bloco SWITCH";
                String justification = "Controle de fluxo baseado em múltiplos valores de caso";

                // Slice do switch inteiro
                List<String> sliceCode = new ArrayList<>();
                sliceCode.add(switchStmt.toString());
                List<String> vars = extractVariables(switchStmt);
                Map<String, Integer> outsideCount = calculateUsageOutsideSlice(switchStmt, vars, variableUsageFreq, loopVars);
                int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();

                slices.add(new Slice(label, justification, sliceCode, vars, outsideCount, totalOutside));

                // Agora, para cada case, agrupar os statements num único slice
                /*for (SwitchEntry entry : switchStmt.getEntries()) {
                    if (!entry.getStatements().isEmpty()) {
                        List<String> caseSlice = new ArrayList<>();
                        BlockStmt syntheticBlock = new BlockStmt();

                        for (Statement innerStmt : entry.getStatements()) {
                            caseSlice.add(innerStmt.toString());
                            syntheticBlock.addStatement(innerStmt);
                        }

                        List<String> varsInner = extractVariables(syntheticBlock);
                        Map<String, Integer> outsideCountInner = calculateUsageOutsideSlice(syntheticBlock, varsInner, variableUsageFreq, loopVars);
                        int totalOutsideInner = outsideCountInner.values().stream().mapToInt(Integer::intValue).sum();

                        slices.add(new Slice(
                            "Case do SWITCH",
                            "Conjunto de instruções pertencente a um dos casos do switch",
                            caseSlice,
                            varsInner,
                            outsideCountInner,
                            totalOutsideInner
                        ));
                    }
                }*/
            }
 else if (stmt.isBlockStmt()) {
                // Bloco de código isolado
                saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);
                analyzeBlock(stmt.asBlockStmt(), slices, variableUsageFreq,loopVars);

            } else {
                // Acumula statements sequenciais
                if (currentLabel == null) {
                    currentLabel = "Bloco de código sequencial";
                    currentJustification = "Grupo de instruções sequenciais sem estruturas de controle";
                }
                currentSliceStatements.add(stmt.toString());
            }
        }

        saveCurrentSlice(slices, currentLabel, currentJustification, currentSliceStatements, variableUsageFreq,loopVars);
    }

    


    
    private static void saveCurrentSlice(List<Slice> slices, String label, String justification, List<String> statements, Map<String, Integer> variableUsageFreq, Set<String> loopVars)
 {
    	
    	if (statements != null && !statements.isEmpty()) {
    		String code = String.join("\n", statements);
    		try {
    			// Tenta parsear como bloco
    			BlockStmt block = StaticJavaParser.parseBlock("{" + code + "}");

    			List<String> vars = extractVariables(block);
    			Map<String, Integer> outsideCount = calculateUsageOutsideSlice(block, vars, variableUsageFreq,loopVars);
    			int totalOutside = outsideCount.values().stream().mapToInt(Integer::intValue).sum();

    			slices.add(new Slice(label, justification, Collections.singletonList(code), vars, outsideCount, totalOutside));
    		} catch (Exception e) {
    			System.err.println("Erro ao parsear slice:\n" + code);
    			e.printStackTrace(); // ajuda no debug
    			slices.add(new Slice(label, justification, new ArrayList<>(statements),
    					Collections.emptyList(), Collections.emptyMap(), 0));
    		}

    		statements.clear();
    	}
}


   public static List<String> extractVariables(Node node) {
        Set<String> vars = new HashSet<>();

        // Variáveis usadas (NameExpr)
        node.walk(NameExpr.class, nameExpr -> {
            vars.add(nameExpr.getNameAsString());
        });

        // Variáveis declaradas (VariableDeclarator)
        node.walk(com.github.javaparser.ast.body.VariableDeclarator.class, varDecl -> {
            vars.add(varDecl.getNameAsString());
        });

        List<String> sortedVars = new ArrayList<>(vars);
        Collections.sort(sortedVars);
        return sortedVars;
    }
   
   public static Set<String> extractLoopControlVariables(MethodDeclaration method) {
	    Set<String> loopVars = new HashSet<>();

	    method.findAll(ForStmt.class).forEach(forStmt -> {
	        for (Expression initExpr : forStmt.getInitialization()) {
	            if (initExpr.isVariableDeclarationExpr()) {
	                for (VariableDeclarator var : initExpr.asVariableDeclarationExpr().getVariables()) {
	                    loopVars.add(var.getNameAsString());
	                }
	            }
	        }
	    });

	    method.findAll(ForEachStmt.class).forEach(forEachStmt -> {
	        for (VariableDeclarator var : forEachStmt.getVariable().getVariables()) {
	            loopVars.add(var.getNameAsString());
	        }
	    });

	    return loopVars;
	}

   
   

    public static Map<String, Integer> countVariableUsageInMethod(MethodDeclaration method) {
        Map<String, Integer> freqMap = new HashMap<>();
        method.walk(NameExpr.class, nameExpr -> {
            String varName = nameExpr.getNameAsString();
            freqMap.put(varName, freqMap.getOrDefault(varName, 0) + 1);
        });
        
        method.walk(com.github.javaparser.ast.body.VariableDeclarator.class, varDecl -> {
            String varName = varDecl.getNameAsString();
            freqMap.put(varName, freqMap.getOrDefault(varName, 0) + 1);
        });
        
        return freqMap;
    }

    public static Map<String, Integer> countVariableUsageInSlice(Statement stmt) {
        Map<String, Integer> freqMap = new HashMap<>();
        stmt.walk(NameExpr.class, nameExpr -> {
            String varName = nameExpr.getNameAsString();
            freqMap.put(varName, freqMap.getOrDefault(varName, 0) + 1);
        });
        
        stmt.walk(com.github.javaparser.ast.body.VariableDeclarator.class, varDecl -> {
            String varName = varDecl.getNameAsString();
            freqMap.put(varName, freqMap.getOrDefault(varName, 0) + 1);
        });
        
        return freqMap;
    }


    
    public static Map<String, Integer> calculateUsageOutsideSlice(Statement stmt, List<String> vars, Map<String, Integer> variableUsageFreq, Set<String> loopVars)
   {
        Map<String, Integer> usageInSlice = countVariableUsageInSlice(stmt);
        Map<String, Integer> usageOutsideSlice = new HashMap<>();

        for (String v : vars) {
        	if (loopVars.contains(v)) continue;  // <-- PULAR VARIÁVEL DE CONTROLE

            int totalInMethod = variableUsageFreq.getOrDefault(v, 0);
            int inSlice = usageInSlice.getOrDefault(v, 0);
            int outside = Math.max(0, totalInMethod - inSlice);
            usageOutsideSlice.put(v, outside);
        }

        return usageOutsideSlice;
    }

    
    
    public static boolean deveExcluirSlice(String sourceCodeFragment, String methodCode) {
        // Normaliza o slice
        String frag = sourceCodeFragment.replaceAll("[\\s\\r\\n]+", "");

        // Extrai e normaliza o corpo do método original
        String methodBody = "";
        try {
            CompilationUnit cu = StaticJavaParser.parse("class Temp { " + methodCode + " }");
            MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
            String bodyStr = method.getBody().get().toString();
            if (bodyStr.startsWith("{") && bodyStr.endsWith("}")) {
                bodyStr = bodyStr.substring(1, bodyStr.length() - 1);  // remove {}
            }
            methodBody = bodyStr.replaceAll("[\\s\\r\\n]+", "");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Regra 1: Slice igual ao método inteiro
        if (frag.equals(methodBody)) return true;

        // Regra 2: break ou continue isolado
        boolean containsBreak = frag.contains("break;");
        boolean containsContinue = frag.contains("continue;");
        boolean isSimpleStatement = !frag.contains("{") && !frag.contains("}");
        
        if (frag.startsWith("throw") && isSimpleStatement) return true;

        if ((containsBreak || containsContinue) && isSimpleStatement) return true;

        return false;
    }





    
}
