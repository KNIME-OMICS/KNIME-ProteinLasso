package uni.tubingen.inference.proteinlasso;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DoubleValue;
import org.knime.core.data.RowIterator;
import org.knime.core.data.RowKey;
import org.knime.core.data.StringValue;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;


import weka.core.Utils;
import org.apache.commons.lang3.StringUtils;


public class ProteinLasso {
	
	private double lambda; 					//the penalty parameter lambda
	private double lambda_max;				// the maximal value of lambda
	private int protein_num, peptide_num;	// the number of candidate proteins and identified peptides in the bipartite graph
	public double[][] X;					// peptide detectability matrix
	public double[] Y;						// peptide probablity vector
	public double[] coef;					// protein probablity vector
	public double[] inter_product;			// the inter product of vector X_j and Y
	public double[][] X_inter_product;		// the inter product of vector X_j and X_K
	public String[] proteinNames;			// the array of protein names
	public String[] peptideSeq;				// the array of peptide sequences
	
	/** the hashtable of peptide sequences */
	private Hashtable<String, Integer> distinct_peptide = new Hashtable<String, Integer>();
	/** the hashtable of peptide probabilities */
	private Hashtable<String, ArrayList<Double>> peptide_prob = new Hashtable<String, ArrayList<Double>>();
	/** the hashtable which saves the relationship between candidate proteins and identified peptides */
	private Hashtable<String, ArrayList<String>> distinct_protein = new Hashtable<String, ArrayList<String>>();
	/** the hashtable of protein names */
	private Hashtable<String, Integer> distinct_protein_pos = new Hashtable<String, Integer>();
	/** the hashtable which stores the information in the peptide detectability file */
	private Hashtable<String, Hashtable<String, Double>> detectability = new Hashtable<String, Hashtable<String, Double>>();
	/** the hashtable which stores [the median(predicted detectability scores from the same parent protein)/3] for each protein */
	private Hashtable<String, Double> protein_detect = new Hashtable<String, Double>();
	
	public int get_proteinNum(){
		return protein_num;
	}
	
	public int get_peptideNum(){
		return peptide_num;
	}
	
	public double get_Lambda_max(){
		return lambda_max;
	}
	
	
	/**
	 * load the information in the peptide identification file
	 * @param data_table
	 */
	public void buildPeptideFile(BufferedDataTable data_table){
		try{
			// load the peptide sequences;
			RowIterator row_it = data_table.iterator();
			while (row_it.hasNext()) {
				// getting information from current row...
				DataRow r = row_it.next();
				DataCell pep_cell = r.getCell(ProteinLassoNodeModel.pep_idx);
				
				// rows with missing cells cannot be processed (no missing values in PSM graph...)
				if (pep_cell.isMissing()) {
					continue;
				}
				
				// getting value from cells
				String peptide_entry  =  ((StringValue) pep_cell).getStringValue();
				
				Integer po = (Integer)distinct_peptide.get(peptide_entry); //the hashtable stores all the peptide sequences.
				if (po == null) {
					int pos = distinct_peptide.size();
					distinct_peptide.put(peptide_entry, new Integer(pos));
				}
			}
			
			peptide_num = distinct_peptide.size();
			
			// load the protein information
			peptide_prob = new Hashtable<String, ArrayList<Double>>(peptide_num);
			row_it = data_table.iterator();
			while (row_it.hasNext()) {
				// getting information from current row...
				DataRow r = row_it.next();
				DataCell pep_cell = r.getCell(ProteinLassoNodeModel.pep_idx);
				DataCell accsn_cell= r.getCell(ProteinLassoNodeModel.accsn_idx);
				DataCell proba_cell= r.getCell(ProteinLassoNodeModel.proba_idx);
				
				// rows with missing cells cannot be processed (no missing values in PSM graph...)
				if (pep_cell.isMissing() || accsn_cell.isMissing() || proba_cell.isMissing()) {
					continue;
				}
				
				// getting value from cells
				String peptide_entry = ((StringValue)pep_cell).getStringValue();
				String protein_accsn = ((StringValue)accsn_cell).getStringValue();
				Double proba_entry   = ((DoubleValue) proba_cell).getDoubleValue();
				
				ArrayList<Double> prob = peptide_prob.get(peptide_entry);//peptide_prob hashtable stores all the probabilities corresponding to a peptide.
				if(prob==null) {
					prob = new ArrayList<Double>();
					prob.add(proba_entry);
					peptide_prob.put(peptide_entry, prob);
				} else {
					prob.add(proba_entry);
				}
				
				// deal with protein names in the peptide identification file so that these protein names can match with those in the peptide detectability file.
				String [] protein_group = protein_accsn.split(";");
				
				for(int i = 0; i < protein_group.length; i++) {
					ArrayList<String> pt = (ArrayList<String>)distinct_protein.get(formattedProteinAccesion(protein_group[i]));//the hashtable stores the relationship between candidate proteins and identified peptides.
					if(pt==null) {
						pt = new ArrayList<String>();
						pt.add(peptide_entry);
						distinct_protein.put(formattedProteinAccesion(protein_group[i]), pt);
						distinct_protein_pos.put(formattedProteinAccesion(protein_group[i]), distinct_protein_pos.size());
					} else {
						if(!pt.contains(peptide_entry)) {
							pt.add(peptide_entry);
						}
					}
				}
			}
			
			protein_num = distinct_protein.size();
			
			peptideSeq = new String[peptide_num];
			for(Iterator<String> h = distinct_peptide.keySet().iterator(); h.hasNext(); ) {
				String key = (String) h.next();
				int pos = ((Integer)distinct_peptide.get(key)).intValue();
				peptideSeq[pos] = key;
			}
			
			proteinNames = new String[protein_num];
			for (Iterator<String> h = distinct_protein.keySet().iterator(); h.hasNext(); ) {
				String key = (String) h.next();
				int pos = ((Integer)distinct_protein_pos.get(key)).intValue();
				proteinNames[pos] = key;
			}
		} catch (Exception x) {
			ProteinLassoNodeModel.logger.error(x);
		}
	}
	
	
	/**
	 * load the information in the peptide detectability file
	 * @param data_table
	 */
	public void buildDetectabilityFile(BufferedDataTable data_table){
	    
		try{			
	    	RowIterator row_it = data_table.iterator();
	    	while (row_it.hasNext()) {
	    		
	     		//getting information from current row...
	    		DataRow r = row_it.next();
	    		DataCell pep_cell     = r.getCell(ProteinLassoNodeModel.pep_idx);
	    		DataCell accsn_cell   = r.getCell(ProteinLassoNodeModel.accsn_idx);
	    		DataCell detect_cell  = r.getCell(ProteinLassoNodeModel.detect_idx);
	    		
	    		
	    		//rows with missing cells cannot be processed (no missing values in PSM graph...)
	    		if (pep_cell.isMissing() || accsn_cell.isMissing() || detect_cell.isMissing()) {
	    			continue;
	    		}
	    		
	    		//getting value from cells
	    		String peptide_entry   =   ((StringValue) pep_cell).getStringValue();
	    		String protein_accsn   =   ((StringValue) accsn_cell).getStringValue();
	    		Double detect_entry    =   ((DoubleValue)detect_cell).getDoubleValue();
	    		 
	    		String [] protein_group = protein_accsn.split(";");
	    		 
	    		for(int i = 0; i < protein_group.length; i++){				
	    	
	    		   Hashtable<String, Double> pt = detectability.get(formattedProteinAccesion(protein_group[i]));//the hashable stores all the information in the detectability file
				    if(pt==null){
				    	pt = new Hashtable<String, Double>();
				    	pt.put(peptide_entry, detect_entry);
				    	detectability.put(formattedProteinAccesion(protein_group[i]), pt);	
				    }else{
				    	if(pt.get(peptide_entry)==null){
				    		pt.put(peptide_entry, detect_entry);
				    	} else {//if there are two detectabilities for a peptide, we choose the bigger detectability.
				    		if(detect_entry > pt.get(peptide_entry)) {
				    			pt.put(peptide_entry, detect_entry);
				    		}
				    	}
				     }
	    		  }     
	    	   }
			     
				//protein_detect stores [the median(predicted detectability scores from the same parent protein)/3] for each protein
				protein_detect = new Hashtable<String, Double>(detectability.size());
				for(Iterator<String> h = detectability.keySet().iterator();h.hasNext(); ){
					 String key = (String) h.next();
					 
					 Hashtable<String, Double> dt = detectability.get(key);
					 double[] detect_temp = new double[dt.size()];
					 int i=0;
					 for(Iterator<String> d = dt.keySet().iterator();d.hasNext(); ){
						 String peptideseq = (String) d.next();
						 double detect = dt.get(peptideseq);
						 detect_temp[i] = detect;
						 i++;
					 }
					 int[] sorted = Utils.sort(detect_temp);
					 int median = sorted[(int)java.lang.Math.ceil(dt.size()/2)];
					 protein_detect.put(key, detect_temp[median]/3);
				 
				}	
	    	}
	    	catch (Exception x){
	    		ProteinLassoNodeModel.logger.error(x);
		    }
	}

	
	
	public void getcoef(String tag){
		try{	
	    int totalProteins = protein_num;
	    int totalPeptides = peptide_num;
	    
	    X_inter_product=new double[totalProteins][totalProteins];
	    X=new double[totalPeptides][totalProteins];
	    inter_product = new double[totalProteins];
	    for(int i=0;i<totalProteins;i++){
			inter_product[i] = 0;
        	for(int j=0;j<totalPeptides;j++){
        		X[j][i]=0.0;
        	}
        }
		Y=new double[totalPeptides];
		double peptideprob;
		
		//assign the peptide probabilities to array Y;
		//If there are multiple probabilities for a peptide, user can choose the average or maximal value or the constant "1" as the final probability. 
		for(int j=0;j<totalPeptides;j++){	
			ArrayList<Double> arl = peptide_prob.get(peptideSeq[j]);
			if(tag.equals("average")){
				peptideprob=0;
				for(int i=0;i<arl.size();i++){
					peptideprob = peptideprob + arl.get(i);
				}
        		Y[j]=peptideprob/arl.size();
			}
			else if(tag.equals("max")){
				peptideprob = arl.get(0);
				for(int i=0;i<arl.size();i++){
					if(peptideprob < arl.get(i)){
						peptideprob = arl.get(i);
					}
				}
				Y[j]=peptideprob;
			}
			else	Y[j]=1;
    	}
		
		
		//according to hashtable "detectability", we assign the corresponding detectability to matrix X.
		Hashtable<Integer, Hashtable<Integer, Double>> X_hash = new Hashtable<Integer, Hashtable<Integer, Double>>();
		for(Iterator<String> h = distinct_protein.keySet().iterator();h.hasNext(); ){
			 String key = (String) h.next();
			 boolean mark=false;
			 int pos = ((Integer)distinct_protein_pos.get(key)).intValue();
			 Hashtable<String, Double> dt = detectability.get(key);
			 if(dt!=null){
				 	ArrayList<String> al = distinct_protein.get(key);
					for(int i=0;i<al.size();i++){
						mark=false;
						String peptideseq= al.get(i);
						int num = ((Integer)distinct_peptide.get(peptideseq)).intValue();
						int repeat = 0;
						String peptide_temp = "";
						if(dt.get(peptideseq)!=null){
							 double detect = dt.get(peptideseq);
				    		 X[num][pos]= detect;
				    		 mark=true;
						}
						else for(Iterator<?> d = dt.keySet().iterator();d.hasNext(); ){
							 String peptideseq_candidate = (String) d.next();
							 if((peptideseq.contains(peptideseq_candidate))|peptideseq_candidate.contains(peptideseq)){
								 repeat++;
								//if there is no exactly matching peptide, we choose the detectability of the longest peptide sequence which contains the identified peptide sequence.
								 //if there are two longest peptide sequence containing the identified peptide sequence, we choose the higher detectability.
								 if(repeat == 1){
									 peptide_temp=peptideseq_candidate;
									 double detect = dt.get(peptideseq_candidate);
						    		 X[num][pos]= detect;
								 }
								 else {
									 if(peptide_temp.length()<peptideseq_candidate.length()){
										 peptide_temp=peptideseq_candidate;
										 double detect = dt.get(peptideseq_candidate);
							    		 X[num][pos]= detect;
									 }
									 else  if(peptide_temp.length()==peptideseq_candidate.length()){
										 		double detect = dt.get(peptideseq_candidate);
										 		if(X[num][pos]<detect){
										 			peptide_temp=peptideseq_candidate;
										 			X[num][pos]= detect;
										 		}
									 		}
								 		}
					    		 mark=true;
							 }
						}	
						//if we don't found a matching peptide in the detectability file for the identified peptide, we assign [the median(predicted detectability scores from the same parent protein)/3] to the peptide.
						if(mark==false){
							double detect = (Double)protein_detect.get(key);
							X[num][pos]=detect;
						}
					}	
			 	}
			 	else {
			 		ProteinLassoNodeModel.logger.warn(key + "is not included in the detectability file!");
			 	}
		}
		
		
		
		double temp=0;
		//calculate and save the inter product of X_j and Y for all the proteins.
		for(int i=0;i<totalProteins;i++){
			Hashtable<Integer, Double> pt = (Hashtable<Integer, Double>)X_hash.get(i);
        	for(int j=0;j<totalPeptides;j++){
        		if(X[j][i]!=0){
        			if(pt==null){
        				pt = new Hashtable<Integer, Double>();
        				pt.put(j,X[j][i]);
        				X_hash.put(i, pt);		
        			}else{
        				if(pt.get(j)==null){
        					pt.put(j,X[j][i]);
        				}
        			}
        		}
        		inter_product[i] = inter_product[i]+ X[j][i]*Y[j];
        	}
        	if(temp<inter_product[i]){
        		temp=inter_product[i];
        	}
        }	
		lambda_max=2*temp;//calculate the maximal value of lamda
		
		
		// calculate and save the inter product of X_j and X_K for all the proteins.
		for(int m=0;m<totalProteins;m++){
			for(int i=m;i<totalProteins;i++){
				X_inter_product[m][i] = 0;
				Hashtable<Integer, Double> xh_m = X_hash.get(m);
				Hashtable<Integer, Double> xh_i = X_hash.get(i);
				for(Iterator<Integer> h =xh_m.keySet().iterator();h.hasNext();){
					 int key = Integer.parseInt(h.next().toString());
					 double detect_m= Double.parseDouble(xh_m.get(key).toString());
					 if(xh_i.get(key)!=null){
						double detect_i= Double.parseDouble(xh_i.get(key).toString());
						X_inter_product[m][i] = X_inter_product[m][i]+ detect_m*detect_i;
					 }
				}
				X_inter_product[i][m]=X_inter_product[m][i];	
			}	
		}
		
	}
	catch (Exception x){
		ProteinLassoNodeModel.logger.error(x);
	}
	}
	
	
	final static double ePowMinus5 = java.lang.Math.pow(Math.E,-5);
	
	
	//the coordinate descent algorithm
	public double[] Coordinate_Descent(double[] coef_original, double Lamda) {
		try {
			lambda=Lamda;
			int i=0,j=0;
			double y_j;
	
			coef = new double[protein_num];//protein probability vector
			double sum[]=new double[protein_num];
	
			for(j=0;j<protein_num;j++){
				coef[j] = coef_original[j];
				for(i=0;i<peptide_num;i++){
					sum[j]=sum[j]+X[i][j]*X[i][j];
				}
			}
			
			boolean hasUpdate = true;
			while(hasUpdate) {
				hasUpdate = false;
				
				for(j = 0; j < protein_num; j++){
					y_j = 0;
					for(i = 0; i < protein_num; i++){
						if(coef[i] != 0) {
							y_j = y_j +X_inter_product[j][i]*coef[i];
						}
					}
					
					double y_j_temp = inter_product[j]+sum[j]*coef[j]-y_j;
					double coef_j_temp = (y_j_temp-0.5*lambda)/sum[j];
					if (coef_j_temp<0) {
						coef_j_temp=0;
					}
					
					if (coef_j_temp>1) {
						coef_j_temp=1;
					}
					
					if (Math.abs(coef[j]-coef_j_temp) > ePowMinus5) {
						hasUpdate = true;
						coef[j]=coef_j_temp;
					}
				}
			}
	    } catch (Exception x) {
	    	ProteinLassoNodeModel.logger.error(x);
	    }
	    
	    return coef;
	}
	
	/**
	 * print the proteins and their probabilities in descending order.
	 * @param container
	 * @param Probability
	 */
	public void writeContainer(BufferedDataContainer container, double[] Probability) {
		try{
			int[] pos = weka.core.Utils.sort(Probability);
			
			for(int i=0;i<protein_num;i++) {
				int bestIndex = protein_num-i-1;
				
				RowKey key = new RowKey(proteinNames[pos[bestIndex]]);
				DataCell[] cells = new DataCell[4];
				cells[0] = new StringCell(proteinNames[pos[bestIndex]]);
				cells[1] = new DoubleCell(Probability[pos[bestIndex]]);
				
				Set<String> sequenceWithoutMods = new HashSet<String>();
        		for (String modSeq : distinct_protein.get(proteinNames[pos[bestIndex]])) {
        			sequenceWithoutMods.add(modSeq.replaceAll("\\([^\\)]+\\)", ""));
        		}
				
				cells[2] = new IntCell(distinct_protein.get(proteinNames[pos[bestIndex]]).size());
				cells[3] = new IntCell(sequenceWithoutMods.size());
				
				DataRow row = new DefaultRow(key, cells);
				container.addRowToTable(row);
			}
		} catch (Exception x) {
			ProteinLassoNodeModel.logger.error(x);
		}
	}
	
	
	/**
	 * this function is for formatting long protein name (getting universal ID...)
	 * @param protein_accn
	 * @return
	 */
	static private String formattedProteinAccesion (String protein_accn){
		if(protein_accn.contains("|")){
			return StringUtils.substringBeforeLast(protein_accn, "|");
		} else if(protein_accn.contains(" ")) {
			return protein_accn.trim();
		} else {
			return protein_accn;
		}
	}
}

