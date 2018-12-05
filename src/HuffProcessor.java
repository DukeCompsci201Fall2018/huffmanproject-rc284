import java.util.PriorityQueue;

/**
 * Although this class has a history of several years,
 * it is starting from a blank-slate, new and clean implementation
 * as of Fall 2018.
 * <P>
 * Changes include relying solely on a tree for header information
 * and including debug and bits read/written information
 * 
 * @author Owen Astrachan
 */

public class HuffProcessor {

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); 
	public static final int PSEUDO_EOF = ALPH_SIZE;
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE  = HUFF_NUMBER | 1;

	private final int myDebugLevel;
	
	public static final int DEBUG_HIGH = 4;
	public static final int DEBUG_LOW = 1;
	
	public HuffProcessor() {
		this(0);
	}
	
	public HuffProcessor(int debug) {
		myDebugLevel = debug;
	}

	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void compress(BitInputStream in, BitOutputStream out){

		// Step 1: Determine frequency of every 8-bit char in file
		int[] counts = readForCounts(in);

		// Step 2: From frequencies, create HuffTree to create encodings
		HuffNode root = makeTreeFromCounts(counts);

		// Step 3: From HuffTree, create encodings for each 8-bit char
		String[] codings = new String[ALPH_SIZE + 1];
		makeCodingsFromTree(root,"",codings);

		// Step 4: Write "magic" number and tree to header of compressed file
		out.writeBits(BITS_PER_INT, HUFF_TREE);
		writeHeader(root, out);

		// Step 5: Read file again. write encoding for each 8-bits, end with PSEUDO_EOF and file close
		in.reset();
		writeCompressedBits(codings, in, out);
		out.close();
	}

	/**
	 * Count freqeuncy of each character in input and store in integer array
	 * @param in
	 */
	private int[] readForCounts(BitInputStream in) {
		int[] freq = new int[ALPH_SIZE + 1]; // 257 values --> 256 possible ASCII + PSEUDO_EOF
		freq[PSEUDO_EOF] = 1; // Indicate one occurance of PSEUDO_EOF

		for (int i = 0; i < freq.length; i++) {
			int bits = in.readBits(BITS_PER_WORD); // Read 8 bits (a char)
			if (bits == -1) {break;} // If sentinel -1 bit, break
			freq[bits]++; // increase freq of that bit by 1
		}

		return freq;
	}

	/**
	 * Create HuffTree using frequency counts of characters
	 * @param freq
	 */
	private HuffNode makeTreeFromCounts(int[] freq) {
		PriorityQueue<HuffNode> pq = new PriorityQueue<>();
		
		// Create individual HuffTrees for every char with > 0 freq
		for (int i = 0; i < freq.length; i++) {
			if (freq[i] > 0) {
				pq.add(new HuffNode(i, freq[i], null, null));
			}
		}

		while (pq.size() > 1) { 
			// Get two least weight HuffTrees that are in front of queue due to comparable
			HuffNode left = pq.remove();
			HuffNode right = pq.remove();
			// Create new HuffTree w/ weight from left + right and left,right subtrees
			HuffNode newTree = new HuffNode(-1, left.myWeight + right.myWeight, left, right);
			pq.add(newTree);
		}

		return pq.remove(); // When pq size is 1, all trees have been combined into 1
	}

	/**
	 * Generate encodings from HuffTree
	 * @param root
	 * @param path
	 * @param codings
	 */
	private void makeCodingsFromTree(HuffNode root, String path, String[] codings) {
		if (root.myLeft == null && root.myRight == null) { // If leaf node
			codings[root.myValue] = path; // End path and return
			return;
		}
		// Else, recursive calls, left add 0 to path, right add 1 to path
		makeCodingsFromTree(root.myLeft, path + "0", codings);
		makeCodingsFromTree(root.myRight, path + "1", codings);
	}

	/**
	 * Write tree to output file header
	 * @param root
	 * @param out
	 */
	private void writeHeader(HuffNode root, BitOutputStream out) {
		if (root.myLeft != null || root.myRight != null) { // If node not a leaf
			out.writeBits(1,0); // Add 0 to preorder
			writeHeader(root.myLeft, out);
			writeHeader(root.myRight, out);
		}
		else { // If node a leaf
			out.writeBits(1,1); // Add 1 to preorder
			out.writeBits(BITS_PER_WORD + 1, root.myValue); // Add 8-bit val to preorder
		}
	}

	/**
	 * Write compressed bits to output file
	 * @param codings
	 * @param in
	 * @param out
	 */
	private void writeCompressedBits(String[] codings, BitInputStream in, BitOutputStream out) {
		
		while (true) {
			int bit = in.readBits(BITS_PER_WORD);
			if (bit == -1) {break;}

			String code = codings[bit]; // Get encoding for bit
			out.writeBits(code.length(), Integer.parseInt(code,2)); // Write encoded bit to out
		}

		String code = codings[PSEUDO_EOF]; // Get encoding for PSEUDO_EOF
		out.writeBits(code.length(), Integer.parseInt(code,2)); // End file with encoded PSEUDO_EOF
	}


	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void decompress(BitInputStream in, BitOutputStream out){

		// Step 1: Read "magic" number
		int bits = in.readBits(BITS_PER_INT);

		// Step 2: Read tree used to decompress
		if (bits != HUFF_TREE || bits == -1) {
			throw new HuffException("illegal header starts with " + bits);
		}

		// Step 3: Read bits from file and traverse root-to-leaf
		HuffNode root = readTreeHeader(in);
		readCompressedBits(root,in,out);

		// Step 4: Close file
		out.close();
	}

	/**
	 * Read pre-order traversed interpretation to construct tree
	 * @param in
	 */
	private HuffNode readTreeHeader(BitInputStream in) {
		int bit = in.readBits(1);

		if (bit == -1) { // Bit = -1 --> Bad bit
			throw new HuffException("bad bit");
		}

		if (bit == 0) { // Bit = 0 --> Recurse to continue tree
			HuffNode left = readTreeHeader(in);
			HuffNode right = readTreeHeader(in);
			return new HuffNode(0, 0, left, right);
		}
		else { // Bit = 1 --> Leaf reached, end tree
			int value = in.readBits(BITS_PER_WORD + 1);
			return new HuffNode(value, 0, null, null);
		}
	}

	/**
	 * Read bits from BitInputStream representing compressed file one bit at a time
	 * and traverse tree
	 * @param root
	 * @param in
	 * @param out
	 */
	private void readCompressedBits(HuffNode root, BitInputStream in, BitOutputStream out) {
		HuffNode curr = root;

		while (true) {
			int bit = in.readBits(1);

			if (bit == -1) { // Bit == -1 --> bad bit
				throw new HuffException("bad input, no PSEUDO_EOF");
			}
			else { // Bit != -1 --> good bit
				if (bit == 0) { // bit = 0 --> go left
					curr = curr.myLeft;
				}
				else { // bit == 1 --> go right
					curr = curr.myRight;
				}

				if (curr.myLeft == null && curr.myRight == null) { // If bit is leaf
					if (curr.myValue == PSEUDO_EOF) { // break if bit is special ending char
						break;
					}
					else { // otherwise write bit to output file
						out.writeBits(BITS_PER_WORD, curr.myValue);
						curr = root;
					}
				}
			}
		}
	}
}